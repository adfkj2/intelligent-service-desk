package com.isd.ticket.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.result.PageResponse;
import com.isd.ticket.domain.TicketAction;
import com.isd.ticket.domain.TicketStatus;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.dto.TicketCreationResult;
import com.isd.ticket.dto.TicketResponse;
import com.isd.ticket.entity.Ticket;
import com.isd.ticket.entity.TicketOperationLog;
import com.isd.ticket.mapper.TicketMapper;
import com.isd.ticket.mapper.TicketOperationLogMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单业务：状态机、权限、乐观锁、审计日志都在这一层。
 * Controller 只管路由和 DTO，Mapper 只管 SQL —— 业务规则只允许出现在这里。
 */
@Service
public class TicketService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("IT_FAULT", "PERMISSION", "DEVICE", "OTHER");
    private static final int TICKET_NO_MAX_ATTEMPTS = 3;
    private static final int MAX_PAGE_SIZE = 100;
    /** 与 V2 迁移里 idempotency_key 的列长度保持一致 */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    private final TicketMapper ticketMapper;
    private final TicketOperationLogMapper operationLogMapper;
    private final UserMapper userMapper;
    private final IsdMetrics metrics;

    public TicketService(TicketMapper ticketMapper,
                         TicketOperationLogMapper operationLogMapper,
                         UserMapper userMapper,
                         IsdMetrics metrics) {
        this.ticketMapper = ticketMapper;
        this.operationLogMapper = operationLogMapper;
        this.userMapper = userMapper;
        this.metrics = metrics;
    }

    /**
     * 创建工单。事务边界在这一层：插入 + 写审计日志 + 回查必须在同一个事务里。
     *
     * 幂等：请求带 Idempotency-Key 时，同一用户 + 同一 key 只会落一条工单，
     * 重复请求返回第一条并标记 replayed=true（Controller 用 200 + 响应头区分）。
     * key 为空时保持老行为，不影响任何现有调用方。
     */
    /** 不带幂等键的创建（内部调用用）：等价于老行为，每次都会新建 */
    @Transactional
    public TicketCreationResult create(CreateTicketRequest request, LoginUser operator) {
        return create(request, operator, null);
    }

    @Transactional
    public TicketCreationResult create(CreateTicketRequest request, LoginUser operator, String rawIdempotencyKey) {
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);

        // 快路径：绝大多数重放（用户双击、前端重试）在这里命中，避免走异常分支
        if (idempotencyKey != null) {
            Optional<Ticket> replayed = ticketMapper
                    .findByCreatorAndIdempotencyKey(operator.getId(), idempotencyKey);
            if (replayed.isPresent()) {
                metrics.recordIdempotentReplay("CREATE_TICKET");
                return new TicketCreationResult(TicketResponse.from(replayed.get()), true);
            }
        }

        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setCategory(request.category());
        ticket.setPriority(request.priority() == null ? DEFAULT_PRIORITY : request.priority());
        ticket.setStatus(TicketStatus.OPEN.name());
        ticket.setCreatorId(operator.getId());
        ticket.setIdempotencyKey(idempotencyKey);

        Ticket persisted = insertOrReplay(ticket);
        if (persisted != ticket) {
            // 并发同 key：唯一索引挡下了第二次插入，返回先落地的那条
            metrics.recordIdempotentReplay("CREATE_TICKET");
            return new TicketCreationResult(TicketResponse.from(persisted), true);
        }

        writeLog(ticket.getId(), operator.getId(), "CREATE", null, TicketStatus.OPEN.name(), "创建工单");
        return new TicketCreationResult(TicketResponse.from(reload(ticket.getId())), false);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id, LoginUser viewer) {
        return TicketResponse.from(requireVisible(id, viewer));
    }

    /**
     * 分页查询。数据范围在这里收口：
     * EMPLOYEE 只能看自己创建的（强制覆盖 creatorId），SUPPORT / ADMIN 看全部。
     * 如果把这个判断放到 Controller，新增一个查询接口就要再写一遍，漏一次就是数据泄露。
     */
    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> list(String status, String category, String keyword,
                                             int page, int size, LoginUser viewer) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        String statusFilter = isBlank(status) ? null : TicketStatus.of(status).name();
        String categoryFilter = isBlank(category) ? null : normalizeCategory(category);
        String keywordFilter = isBlank(keyword) ? null : likePattern(keyword);
        Long creatorFilter = viewer.getRole() == UserRole.EMPLOYEE ? viewer.getId() : null;

        long total = ticketMapper.countSearch(creatorFilter, statusFilter, categoryFilter, keywordFilter);
        if (total == 0) {
            return PageResponse.of(safePage, safeSize, 0, List.of());
        }

        int offset = (safePage - 1) * safeSize;
        List<TicketResponse> items = ticketMapper
                .search(creatorFilter, statusFilter, categoryFilter, keywordFilter, offset, safeSize)
                .stream()
                .map(TicketResponse::from)
                .toList();
        return PageResponse.of(safePage, safeSize, total, items);
    }

    @Transactional(readOnly = true)
    public long countByStatus(String status) {
        return ticketMapper.countByStatus(TicketStatus.of(status).name());
    }

    /**
     * 接单（乐观锁）。
     * 两个人同时点"接单"时：都读到 version=0，但只有一条 UPDATE 能命中 version=0，
     * 另一条的 affected rows = 0，转成 409 让前端提示"手慢了"。
     */
    @Transactional
    public TicketResponse claim(Long id, LoginUser operator) {
        if (operator.getRole() != UserRole.SUPPORT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有技术支持可以接单");
        }
        Ticket ticket = loadOrThrow(id);
        TicketStatus current = TicketStatus.of(ticket.getStatus());
        if (!TicketAction.CLAIM.allowedFrom(current)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前状态 " + current + " 不允许接单，只有 OPEN 可以接单");
        }

        int updated = ticketMapper.updateStatusWithVersion(
                id, current.name(), TicketAction.CLAIM.to().name(), operator.getId(), ticket.getVersion());
        if (updated == 0) {
            metrics.recordConflict("CLAIM");
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人接单，请刷新后重试");
        }

        writeLog(id, operator.getId(), TicketAction.CLAIM.name(), current.name(),
                TicketAction.CLAIM.to().name(), "接单");
        return TicketResponse.from(reload(id));
    }

    /**
     * 指派（ADMIN）。与接单共用同一条乐观锁 UPDATE，区别只是 assigneeId 由管理员指定。
     */
    @Transactional
    public TicketResponse assign(Long id, Long assigneeId, LoginUser operator) {
        if (operator.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以指派工单");
        }
        Ticket ticket = loadOrThrow(id);
        TicketStatus current = TicketStatus.of(ticket.getStatus());
        if (!TicketAction.ASSIGN.allowedFrom(current)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前状态 " + current + " 不允许指派，只有待接单（OPEN）的工单可以指派");
        }
        User assignee = userMapper.findById(assigneeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "被指派用户不存在: id=" + assigneeId));
        if (assignee.getRole() != UserRole.SUPPORT) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "只能指派给 SUPPORT 角色的用户");
        }
        if (!Integer.valueOf(1).equals(assignee.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "被指派用户已被禁用");
        }

        int updated = ticketMapper.updateStatusWithVersion(
                id, current.name(), TicketAction.ASSIGN.to().name(), assigneeId, ticket.getVersion());
        if (updated == 0) {
            metrics.recordConflict("ASSIGN");
            throw new BusinessException(ErrorCode.CONFLICT, "工单状态已被他人修改，请刷新后重试");
        }

        writeLog(id, operator.getId(), TicketAction.ASSIGN.name(), current.name(),
                TicketAction.ASSIGN.to().name(), "指派给用户 " + assigneeId);
        return TicketResponse.from(reload(id));
    }

    /**
     * 状态变更：RESOLVE（当前处理人 / ADMIN）与 CLOSE（创建者 / ADMIN）。
     * 三个检查的顺序很重要：先看状态机能不能走（409），再看你有没有资格（403），最后才改数据。
     */
    @Transactional
    public TicketResponse changeStatus(Long id, String actionRaw, String comment, LoginUser operator) {
        TicketAction action = TicketAction.of(actionRaw);
        if (action == TicketAction.CLAIM || action == TicketAction.ASSIGN) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "接单请调用 /claim，指派请调用 /assign");
        }

        // 先做数据可见性判断：EMPLOYEE 操作别人的工单统一返回 404（防探测），
        // 而不是 403 —— 403 等于告诉对方"这个 id 真实存在"。
        Ticket ticket = requireVisible(id, operator);
        TicketStatus current = TicketStatus.of(ticket.getStatus());
        if (!action.allowedFrom(current)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前状态 " + current + " 不允许执行 " + action + "（要求 " + action.from() + "）");
        }
        checkActionPermission(action, ticket, operator);

        int updated = ticketMapper.updateStatusWithVersion(
                id, current.name(), action.to().name(), ticket.getAssigneeId(), ticket.getVersion());
        if (updated == 0) {
            metrics.recordConflict("STATUS");
            throw new BusinessException(ErrorCode.CONFLICT, "工单状态已被他人修改，请刷新后重试");
        }

        writeLog(id, operator.getId(), action.name(), current.name(), action.to().name(), comment);
        return TicketResponse.from(reload(id));
    }

    @Transactional(readOnly = true)
    public List<TicketOperationLog> operations(Long id, LoginUser viewer) {
        requireVisible(id, viewer);
        return operationLogMapper.findByTicketId(id);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void checkActionPermission(TicketAction action, Ticket ticket, LoginUser operator) {
        boolean isAdmin = operator.getRole() == UserRole.ADMIN;
        switch (action) {
            case RESOLVE -> {
                if (!isAdmin && !Objects.equals(ticket.getAssigneeId(), operator.getId())) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "只有当前处理人或管理员可以标记解决");
                }
            }
            case CLOSE -> {
                if (!isAdmin && !Objects.equals(ticket.getCreatorId(), operator.getId())) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "只有创建者或管理员可以关闭工单");
                }
            }
            default -> throw new BusinessException(ErrorCode.INVALID_PARAM, "不支持的动作: " + action);
        }
    }

    /**
     * 资源级访问控制。
     * EMPLOYEE 看不到别人的工单时返回 404 而不是 403：403 等于告诉对方"这个 id 是存在的"，
     * 攻击者可以靠状态码差异枚举出全公司的工单总量。
     */
    private Ticket requireVisible(Long id, LoginUser viewer) {
        Ticket ticket = loadOrThrow(id);
        if (viewer.getRole() == UserRole.EMPLOYEE && !Objects.equals(ticket.getCreatorId(), viewer.getId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: id=" + id);
        }
        return ticket;
    }

    private Ticket loadOrThrow(Long id) {
        return ticketMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: id=" + id));
    }

    private Ticket reload(Long id) {
        return ticketMapper.findById(id)
                .orElseThrow(() -> new IllegalStateException("工单回查失败，id=" + id));
    }

    private void writeLog(Long ticketId, Long operatorId, String action,
                          String fromStatus, String toStatus, String comment) {
        TicketOperationLog log = new TicketOperationLog();
        log.setTicketId(ticketId);
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setComment(comment);
        operationLogMapper.insert(log);
        // 成功迁移只在这一处落库，指标也在这里记，避免每个调用点各写一遍
        metrics.recordTransition(action, fromStatus, toStatus);
    }

    /**
     * 插入工单。表上有两个唯一键，撞了要区分处理：
     * <ul>
     *   <li>uk_creator_idempotency：并发同 key，说明别人已经建好了，返回那一条（幂等重放）；</li>
     *   <li>uk_ticket_no：单号撞了，换一个重试，3 次仍失败才报冲突。</li>
     * </ul>
     * 返回值与入参不是同一个对象，就表示走了幂等重放分支。
     */
    private Ticket insertOrReplay(Ticket ticket) {
        for (int attempt = 1; ; attempt++) {
            ticket.setTicketNo(generateTicketNo());
            try {
                ticketMapper.insert(ticket);
                return ticket;
            } catch (DuplicateKeyException ex) {
                if (ticket.getIdempotencyKey() != null) {
                    // 必须是锁定读：普通 SELECT 在本事务的快照里看不到对方刚提交的行
                    Optional<Ticket> existing = ticketMapper.findByCreatorAndIdempotencyKeyForUpdate(
                            ticket.getCreatorId(), ticket.getIdempotencyKey());
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                }
                if (attempt >= TICKET_NO_MAX_ATTEMPTS) {
                    throw new BusinessException(ErrorCode.CONFLICT, "工单号生成冲突，请稍后重试");
                }
                // 插入失败时自增主键已被回填，必须清空，否则下次插入会带着旧 id
                ticket.setId(null);
            }
        }
    }

    /**
     * 单号：T + 日期 + 4 位随机数。
     * 生日悖论决定了一天几十单就可能撞号（见 learning-notes），所以必须配合上面的重试。
     * 生产更稳的做法：雪花算法 / Redis INCR / 由自增主键派生。
     */
    private String generateTicketNo() {
        String date = LocalDate.now().format(DATE_FORMAT);
        int suffix = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "T" + date + "-" + suffix;
    }

    /**
     * 幂等键归一化：空值/空白视为"未启用幂等"（老客户端不带这个头）。
     * 只做长度校验，不限制字符集——UUID、雪花串、前端自定义串都应该能用。
     */
    private String normalizeIdempotencyKey(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "Idempotency-Key 长度不能超过 " + IDEMPOTENCY_KEY_MAX_LENGTH + " 个字符");
        }
        return key;
    }

    private String normalizeCategory(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_CATEGORIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "非法分类: " + raw + "，允许值: " + ALLOWED_CATEGORIES);
        }
        return normalized;
    }

    /**
     * LIKE 通配符转义：用户输入的 % 和 _ 必须当普通字符处理，
     * 否则搜一个 "%" 就等于把全表捞出来。这是"通配符注入"，不是 SQL 注入（占位符已经挡住了后者）。
     */
    private String likePattern(String keyword) {
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
