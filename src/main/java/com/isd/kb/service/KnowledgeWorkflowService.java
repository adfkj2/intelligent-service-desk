package com.isd.kb.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.service.AgentService;
import com.isd.auth.domain.UserRole;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.time.BusinessTime;
import com.isd.kb.dto.CreateItemRequest;
import com.isd.kb.dto.KbItemResponse;
import com.isd.kb.dto.KnowledgeWorkflow.*;
import com.isd.kb.mapper.KbItemMapper;
import com.isd.ticket.dto.TicketResponse;
import com.isd.ticket.service.TicketService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeWorkflowService {
    private static final RowMapper<Draft> ROW = (rs, row) -> new Draft(
            rs.getLong("id"), rs.getLong("ticket_id"), rs.getObject("category_id", Long.class),
            rs.getString("title"), rs.getString("content"), rs.getString("tags"),
            rs.getString("environment"), time(rs.getTimestamp("review_due_at")), rs.getString("status"),
            rs.getLong("created_by"), rs.getObject("reviewed_by", Long.class),
            rs.getObject("published_item_id", Long.class), rs.getString("source"), rs.getInt("version"),
            time(rs.getTimestamp("created_at")), time(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;
    private final KbService kb;
    private final TicketService tickets;
    private final AgentService agent;
    private final ObjectMapper json;
    private final KbItemMapper items;
    private final TransactionTemplate transactions;

    public KnowledgeWorkflowService(JdbcTemplate jdbc, KbService kb, TicketService tickets,
                                    AgentService agent, ObjectMapper json, KbItemMapper items,
                                    PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.kb = kb;
        this.tickets = tickets;
        this.agent = agent;
        this.json = json;
        this.items = items;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<Draft> list(LoginUser viewer) {
        requireStaff(viewer);
        return viewer.getRole() == UserRole.ADMIN
                ? jdbc.query("SELECT * FROM kb_draft WHERE status='DRAFT' ORDER BY id ASC LIMIT 100", ROW)
                : jdbc.query("SELECT * FROM kb_draft WHERE created_by=? AND status='DRAFT' ORDER BY id ASC LIMIT 100", ROW, viewer.getId());
    }

    /** Model IO deliberately runs outside a transaction; drafts are never included in RAG. */
    public Draft fromTicket(FromTicket request, LoginUser operator) {
        requireStaff(operator);
        TicketResponse ticket = tickets.getById(request.ticketId(), operator);
        requireResolved(ticket);
        String solution = tickets.operations(ticket.id(), operator).stream()
                .filter(log -> "RESOLVE".equals(log.getAction()))
                .map(log -> log.getComment() == null ? "" : log.getComment())
                .filter(text -> !text.isBlank()).reduce((before, latest) -> latest)
                .orElse("尚未记录明确的解决步骤，请根据实际处理补充。");
        String context = "现象：" + ticket.title() + "\n" + text(ticket.description(), 2000)
                + "\n已记录的解决说明：" + text(solution, 2000)
                + "\n适用环境：" + (blank(request.environment()) ? "待确认" : request.environment());
        String title = text(ticket.title(), 128);
        String content = "## 适用环境\n" + (blank(request.environment()) ? "请审核并填写适用环境" : request.environment())
                + "\n\n## 故障现象\n" + text(ticket.title(), 128)
                + "\n\n## 处理步骤\n" + text(solution, 8000)
                + "\n\n## 验证方法\n请审核并补充解决后的验证方法。";
        String tags = null;
        String source = "FALLBACK";
        ChatAnswerResponse answer = agent.chatWithContext(
                "请将授权资料整理为可供审核的知识草稿，只返回 JSON：{\"title\":\"标题\",\"content\":\"正文\",\"tags\":\"逗号分隔标签\"}。"
                + "标题最多128字，正文最多20000字，标签最多255字。不要包含工单号、姓名、联系方式、账号、IP或其他个人信息。"
                + "只归纳已有处理事实；缺少的验证方法和环境明确写待审核，不能编造已经成功的步骤。", context, ticket.title(), "KNOWLEDGE_DRAFT");
        if ("LLM".equals(answer.source())) {
            try {
                String raw = answer.answer().trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                JsonNode node = json.readTree(raw);
                String proposedTitle = node.path("title").asText();
                String proposedContent = node.path("content").asText();
                if (!blank(proposedTitle) && !blank(proposedContent)
                        && proposedTitle.length() <= 128 && proposedContent.length() <= 20000) {
                    title = proposedTitle;
                    content = proposedContent;
                    tags = text(node.path("tags").asText(""), 255);
                    source = "LLM";
                }
            } catch (java.io.IOException ignored) {
                // Invalid model JSON leaves the factual, reviewable fallback draft intact.
            }
        }
        if ("LLM".equals(answer.source()) && "FALLBACK".equals(source)) agent.markInvalidOutput(answer);
        String finalTitle = title;
        String finalContent = content;
        String finalTags = tags;
        String finalSource = source;
        // Lock only after model IO. A concurrent reopen cannot slip between recheck and insert.
        return transactions.execute(transaction -> {
            String currentStatus = jdbc.queryForObject("SELECT status FROM ticket WHERE id=? FOR UPDATE", String.class, ticket.id());
            if (!"RESOLVED".equals(currentStatus) && !"CLOSED".equals(currentStatus)) {
                throw new BusinessException(ErrorCode.CONFLICT, "工单已重新打开，请解决后再整理知识");
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO kb_draft (ticket_id,title,content,tags,environment,review_due_at,created_by,source)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, ticket.id());
                statement.setString(2, finalTitle);
                statement.setString(3, finalContent);
                statement.setString(4, finalTags);
                statement.setString(5, request.environment());
                statement.setTimestamp(6, Timestamp.valueOf(BusinessTime.now().plusMonths(3)));
                statement.setLong(7, operator.getId());
                statement.setString(8, finalSource);
                return statement;
            }, key);
            return get(Objects.requireNonNull(key.getKey()).longValue(), operator, false);
        });
    }

    @Transactional
    public Draft edit(Long id, EditDraft request, LoginUser operator) {
        Draft draft = get(id, operator, true);
        requireUnpublished(draft);
        if (request.categoryId() != null) requireCategory(request.categoryId());
        validateReviewDate(request.reviewDueAt());
        int updated = jdbc.update("""
                UPDATE kb_draft SET title=?,content=?,tags=?,category_id=?,environment=?,review_due_at=?,version=version+1
                WHERE id=? AND version=? AND status='DRAFT'
                """, request.title().trim(), request.content(), request.tags(), request.categoryId(), request.environment(),
                request.reviewDueAt(), id, request.version());
        if (updated == 0) throw conflict();
        return get(id, operator, false);
    }

    @Transactional
    public KbItemResponse publish(Long id, Publish request, LoginUser operator) {
        Draft draft = get(id, operator, true);
        if ("PUBLISHED".equals(draft.status())) return kb.getItem(draft.publishedItemId());
        if (draft.version() != request.version()) throw conflict();
        requireCategory(request.categoryId());
        validateReviewDate(draft.reviewDueAt());
        KbItemResponse item = kb.createItem(new CreateItemRequest(request.categoryId(), draft.title(), draft.content(), draft.tags()), operator);
        items.updateReviewMetadata(item.id(), draft.environment(), draft.reviewDueAt(), operator.getId(), draft.ticketId());
        jdbc.update("""
                UPDATE kb_draft SET status='PUBLISHED',category_id=?,reviewed_by=?,published_item_id=?,version=version+1
                WHERE id=?
                """, request.categoryId(), operator.getId(), item.id(), id);
        return kb.getItem(item.id());
    }

    @Transactional
    public KbItemResponse review(Long id, Review request, LoginUser operator) {
        requireStaff(operator);
        KbItemResponse item = kb.getItem(id);
        if (operator.getRole() != UserRole.ADMIN && !operator.getId().equals(item.createdBy())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有创建者或管理员可以复核此条目");
        }
        validateReviewDate(request.reviewDueAt());
        items.updateReviewMetadata(id, request.environment(), request.reviewDueAt(), operator.getId(), item.sourceTicketId());
        return kb.getItem(id);
    }

    @Transactional(readOnly = true)
    public FeedbackSummary feedback(Long id, LoginUser viewer) {
        kb.getItem(id);
        Long helpful = jdbc.queryForObject("SELECT COUNT(*) FROM kb_feedback WHERE item_id=? AND helpful=1", Long.class, id);
        Long unhelpful = jdbc.queryForObject("SELECT COUNT(*) FROM kb_feedback WHERE item_id=? AND helpful=0", Long.class, id);
        List<Boolean> mine = jdbc.query("SELECT helpful FROM kb_feedback WHERE item_id=? AND user_id=?",
                (rs, row) -> rs.getBoolean(1), id, viewer.getId());
        return new FeedbackSummary(helpful == null ? 0 : helpful, unhelpful == null ? 0 : unhelpful,
                mine.isEmpty() ? null : mine.getFirst());
    }

    @Transactional
    public FeedbackSummary feedback(Long id, Feedback request, LoginUser operator) {
        kb.getItem(id);
        jdbc.update("INSERT INTO kb_feedback (item_id,user_id,helpful) VALUES (?,?,?) ON DUPLICATE KEY UPDATE helpful=VALUES(helpful)",
                id, operator.getId(), request.helpful());
        return feedback(id, operator);
    }

    private Draft get(Long id, LoginUser viewer, boolean locked) {
        requireStaff(viewer);
        List<Draft> rows = jdbc.query("SELECT * FROM kb_draft WHERE id=?" + (locked ? " FOR UPDATE" : ""), ROW, id);
        if (rows.isEmpty() || (viewer.getRole() != UserRole.ADMIN && !viewer.getId().equals(rows.getFirst().createdBy()))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识草稿不存在");
        }
        return rows.getFirst();
    }

    private void requireCategory(Long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_category WHERE id=?", Integer.class, id);
        if (count == null || count == 0) throw new BusinessException(ErrorCode.NOT_FOUND, "知识分类不存在");
    }
    private static void requireResolved(TicketResponse ticket) {
        if (!"RESOLVED".equals(ticket.status()) && !"CLOSED".equals(ticket.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "请先解决工单，再整理为知识草稿");
        }
    }
    private static void requireUnpublished(Draft draft) {
        if (!"DRAFT".equals(draft.status())) throw new BusinessException(ErrorCode.CONFLICT, "草稿已发布，请到知识条目编辑");
    }
    private static void requireStaff(LoginUser user) {
        if (user.getRole() == UserRole.EMPLOYEE) throw new BusinessException(ErrorCode.FORBIDDEN, "只有技术支持或管理员可以审核知识");
    }
    private static void validateReviewDate(LocalDateTime date) {
        if (date != null && !date.isAfter(BusinessTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "下次复核时间必须晚于当前时间");
        }
    }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT, "草稿已被修改，请刷新后重试"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String text(String value, int limit) { return value == null ? "" : value.substring(0, Math.min(value.length(), limit)); }
    private static LocalDateTime time(Timestamp stamp) { return stamp == null ? null : stamp.toLocalDateTime(); }
}
