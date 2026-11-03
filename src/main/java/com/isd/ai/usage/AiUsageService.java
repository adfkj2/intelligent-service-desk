package com.isd.ai.usage;

import com.isd.ai.admin.AiRuntimeSnapshot;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.PageResponse;
import com.isd.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Independent, metadata-only ledger. Each real HTTP attempt is counted exactly once. */
@Service
public class AiUsageService {
    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private static final Set<String> PURPOSES = Set.of("CHAT", "DRAFT", "ASSIST", "KNOWLEDGE_DRAFT", "CONNECTION_TEST");
    private static final Set<String> OUTCOMES = Set.of("PENDING", "SUCCESS", "FALLBACK", "TIMEOUT", "ERROR");
    private static final String TOTALS = "COUNT(*) calls,COALESCE(SUM(c.upstream_requests),0) requests,"
            + "COALESCE(SUM(c.input_tokens),0) inputs,COALESCE(SUM(c.cached_input_tokens),0) cached,"
            + "COALESCE(SUM(c.output_tokens),0) outputs,"
            + "COALESCE(SUM(CASE WHEN c.business_outcome IN ('ERROR','TIMEOUT','FALLBACK') THEN 1 ELSE 0 END),0) failures";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public AiUsageService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UsageInvocation begin(AiRuntimeSnapshot snapshot, Long userId, String purpose) {
        if (!PURPOSES.contains(purpose)) throw invalid("不支持的 AI 调用用途");
        String id = UUID.randomUUID().toString();
        try {
            independent.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO ai_usage_call(id,trace_id,user_id,purpose,configuration_id,configuration_version,
                      provider,configuration_name,key_suffix,model,currency,input_price,cached_input_price,output_price,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, MDC.get("traceId"), userId, purpose, snapshot.configurationId(), snapshot.configurationVersion(),
                    snapshot.provider(), snapshot.name(), snapshot.keySuffix(), snapshot.model(), snapshot.currency(),
                    snapshot.inputPrice(), snapshot.cachedInputPrice(), snapshot.outputPrice(), BusinessTime.now()));
        } catch (RuntimeException ex) {
            log.warn("AI 用量台账暂不可写，本次不发起模型请求");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 用量记录暂不可用，请稍后重试");
        }
        return new UsageInvocation(this, id);
    }

    void startAttempt(String id, int number) {
        // An unavailable ledger must prevent an untracked paid request, including later tool rounds.
        independent.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ai_usage_attempt(call_id,attempt_no,created_at) VALUES(?,?,?)", id, number, BusinessTime.now());
            jdbc.update("UPDATE ai_usage_call SET upstream_requests=upstream_requests+1,usage_complete=0,estimated_cost=NULL WHERE id=?", id);
        });
    }

    void completeAttempt(String id, int number, Integer httpStatus, AiUsageInterceptor.ProviderUsage usage,
                         String responseId, String model, long durationMs, boolean success) {
        safely(id, () -> {
            jdbc.update("""
                    UPDATE ai_usage_attempt SET http_status=?,outcome=?,usage_known=?,input_tokens=?,
                      cached_input_tokens=?,output_tokens=?,response_id=?,actual_model=?,completed_at=?,duration_ms=?
                    WHERE call_id=? AND attempt_no=? AND outcome='PENDING'
                    """, httpStatus, success ? "SUCCESS" : "ERROR", usage != null,
                    usage == null ? null : usage.inputTokens(), usage == null ? null : usage.cachedInputTokens(),
                    usage == null ? null : usage.outputTokens(), truncate(responseId, 255), truncate(model, 128),
                    BusinessTime.now(), durationMs, id, number);
            if (model != null) jdbc.update("UPDATE ai_usage_call SET actual_model=? WHERE id=?", truncate(model, 128), id);
            recompute(id);
        });
    }

    void finish(String id, String outcome) {
        safely(id, () -> {
            jdbc.update("""
                    UPDATE ai_usage_call SET upstream_outcome=?,completed_at=?,
                      duration_ms=GREATEST(0,TIMESTAMPDIFF(MICROSECOND,created_at,?)/1000) WHERE id=?
                    """, outcome, BusinessTime.now(), BusinessTime.now(), id);
            recompute(id);
        });
    }

    void businessOutcome(String id, String outcome) {
        if (!OUTCOMES.contains(outcome)) throw invalid("不支持的 AI 调用结果");
        safely(id, () -> jdbc.update("""
                UPDATE ai_usage_call SET business_outcome=? WHERE id=?
                AND (business_outcome='PENDING' OR business_outcome=?)
                """, outcome, id, outcome));
    }

    /** A paid model response can still fail business JSON validation after the worker completed. */
    public void correctFallback(String callId, Long userId) {
        if (callId == null || userId == null) return;
        safely(callId, () -> jdbc.update("""
                UPDATE ai_usage_call SET business_outcome='FALLBACK'
                WHERE id=? AND user_id=? AND business_outcome='SUCCESS'
                  AND purpose IN ('DRAFT','ASSIST','KNOWLEDGE_DRAFT')
                """, callId, userId));
    }

    private void recompute(String id) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) attempts, COALESCE(SUM(input_tokens),0) inputs,
                  COALESCE(SUM(cached_input_tokens),0) cached,COALESCE(SUM(output_tokens),0) outputs,
                  COALESCE(SUM(CASE WHEN usage_known=0 OR outcome='PENDING' THEN 1 ELSE 0 END),0) unknowns
                FROM ai_usage_attempt WHERE call_id=?
                """, id);
        Map<String, Object> call = jdbc.queryForMap("SELECT currency,input_price,cached_input_price,output_price,upstream_outcome FROM ai_usage_call WHERE id=?", id);
        long input = number(totals, "inputs"), cached = number(totals, "cached"), output = number(totals, "outputs");
        boolean complete = number(totals, "unknowns") == 0 && !"PENDING".equals(call.get("upstream_outcome"));
        BigDecimal cost = null;
        if (complete) {
            BigDecimal inputRate = (BigDecimal) call.get("input_price");
            BigDecimal cacheRate = (BigDecimal) call.get("cached_input_price");
            BigDecimal outputRate = (BigDecimal) call.get("output_price");
            if (input + output == 0) cost = BigDecimal.ZERO;
            else if (call.get("currency") != null && (input == cached || inputRate != null)
                    && (cached == 0 || cacheRate != null) && (output == 0 || outputRate != null)) {
                cost = price(input - cached, inputRate).add(price(cached, cacheRate)).add(price(output, outputRate))
                        .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            }
        }
        jdbc.update("""
                UPDATE ai_usage_call SET upstream_requests=?,input_tokens=?,cached_input_tokens=?,output_tokens=?,
                  usage_complete=?,estimated_cost=? WHERE id=?
                """, number(totals, "attempts"), input, cached, output, complete, cost, id);
    }

    private static BigDecimal price(long tokens, BigDecimal rate) {
        return tokens == 0 ? BigDecimal.ZERO : rate.multiply(BigDecimal.valueOf(tokens));
    }

    private void safely(String id, Runnable operation) {
        try { independent.executeWithoutResult(status -> operation.run()); }
        catch (RuntimeException ex) { log.warn("AI 用量写入未完成，调用 {} 将标记为不完整", id); }
    }

    public AiUsageResponses.Summary summary(Map<String, String> parameters) {
        Filter f = filter(parameters);
        Map<String, Object> row = jdbc.queryForMap("SELECT " + TOTALS + ","
                + "COALESCE(SUM(CASE WHEN c.business_outcome='PENDING' OR c.upstream_outcome='PENDING' THEN 1 ELSE 0 END),0) pending,"
                + "COALESCE(SUM(CASE WHEN c.usage_complete=0 THEN 1 ELSE 0 END),0) unknowns FROM ai_usage_call c" + f.where(), f.arguments());
        List<AiUsageResponses.Cost> costs = jdbc.query("""
                SELECT COALESCE(c.currency,'UNCONFIGURED') currency,COALESCE(SUM(c.estimated_cost),0) cost,
                  SUM(CASE WHEN c.estimated_cost IS NULL THEN 1 ELSE 0 END) unknowns FROM ai_usage_call c
                """ + f.where() + " GROUP BY c.currency ORDER BY currency", (rs, n) ->
                new AiUsageResponses.Cost(rs.getString("currency"), rs.getBigDecimal("cost"), rs.getLong("unknowns")), f.arguments());
        return new AiUsageResponses.Summary(number(row, "calls"), number(row, "requests"), number(row, "failures"),
                number(row, "pending"), number(row, "unknowns"), number(row, "inputs"), number(row, "cached"), number(row, "outputs"), costs);
    }

    public List<AiUsageResponses.Daily> daily(Map<String, String> parameters) {
        Filter f = filter(parameters);
        return jdbc.query("SELECT DATE(c.created_at) usage_date," + TOTALS + " FROM ai_usage_call c" + f.where()
                        + " GROUP BY DATE(c.created_at) ORDER BY usage_date", (rs, n) ->
                new AiUsageResponses.Daily(rs.getDate("usage_date").toLocalDate(), rs.getLong("calls"), rs.getLong("requests"),
                        rs.getLong("inputs"), rs.getLong("cached"), rs.getLong("outputs"), rs.getLong("failures")), f.arguments());
    }

    public List<AiUsageResponses.User> users(Map<String, String> parameters) {
        Filter f = filter(parameters);
        return jdbc.query("SELECT c.user_id,u.username,u.full_name," + TOTALS
                        + " FROM ai_usage_call c LEFT JOIN sys_user u ON u.id=c.user_id" + f.where()
                        + " GROUP BY c.user_id,u.username,u.full_name ORDER BY inputs+outputs DESC,c.user_id", (rs, n) ->
                new AiUsageResponses.User(nullableLong(rs, "user_id"), rs.getString("username"), rs.getString("full_name"),
                        rs.getLong("calls"), rs.getLong("requests"), rs.getLong("inputs"), rs.getLong("cached"),
                        rs.getLong("outputs"), rs.getLong("failures")), f.arguments());
    }

    public PageResponse<AiUsageResponses.Call> calls(Map<String, String> parameters) {
        Filter f = filter(parameters);
        int page = positive(parameters.getOrDefault("page", "1"), "页码", 1_000_000);
        int size = positive(parameters.getOrDefault("size", "20"), "每页数量", 100);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_call c" + f.where(), Long.class, f.arguments());
        List<Object> args = new ArrayList<>(java.util.Arrays.asList(f.arguments()));
        args.add(size); args.add((long) (page - 1) * size);
        List<AiUsageResponses.Call> rows = jdbc.query("SELECT c.*,u.username,u.full_name FROM ai_usage_call c LEFT JOIN sys_user u ON u.id=c.user_id"
                        + f.where() + " ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?", (rs, n) ->
                new AiUsageResponses.Call(rs.getString("id"), nullableLong(rs, "user_id"), rs.getString("username"), rs.getString("full_name"),
                        rs.getString("purpose"), nullableLong(rs, "configuration_id"), nullableLong(rs, "configuration_version"),
                        rs.getString("provider"), rs.getString("configuration_name"), rs.getString("key_suffix"), rs.getString("model"),
                        rs.getString("actual_model"), rs.getString("currency"), rs.getString("business_outcome"), rs.getString("upstream_outcome"),
                        rs.getLong("upstream_requests"), rs.getLong("input_tokens"), rs.getLong("cached_input_tokens"), rs.getLong("output_tokens"),
                        rs.getBoolean("usage_complete"), rs.getBigDecimal("estimated_cost"), rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(), rs.getLong("duration_ms")), args.toArray());
        return PageResponse.of(page, size, count == null ? 0 : count, rows);
    }

    private Filter filter(Map<String, String> p) {
        try {
            LocalDate today = BusinessTime.now().toLocalDate();
            LocalDate from = LocalDate.parse(p.getOrDefault("from", today.minusDays(29).toString()));
            LocalDate to = LocalDate.parse(p.getOrDefault("to", today.toString()));
            if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 365 || to.isAfter(today)
                    || from.isBefore(today.minusDays(364))) throw invalid("查询日期须在最近365天内，开始日期不能晚于结束日期");
            StringBuilder where = new StringBuilder(" WHERE c.created_at>=? AND c.created_at<?");
            List<Object> args = new ArrayList<>(); args.add(from.atStartOfDay()); args.add(to.plusDays(1).atStartOfDay());
            for (String name : List.of("provider", "configurationId", "model", "userId", "purpose", "outcome")) {
                String value = p.get(name);
                if (value == null || value.isBlank()) continue;
                if (value.length() > 128) throw invalid("筛选条件过长");
                if ("model".equals(name)) {
                    where.append(" AND (c.model=? OR c.actual_model=?)"); args.add(value); args.add(value);
                    continue;
                }
                String column = switch (name) { case "configurationId" -> "configuration_id"; case "userId" -> "user_id";
                    case "outcome" -> "business_outcome"; default -> name; };
                Object argument = value;
                if (name.endsWith("Id")) { argument = Long.parseLong(value); if ((long) argument <= 0) throw invalid("筛选标识必须为正数"); }
                if ("purpose".equals(name) && !PURPOSES.contains(value)) throw invalid("不支持的调用用途");
                if ("outcome".equals(name) && !OUTCOMES.contains(value)) throw invalid("不支持的调用结果");
                where.append(" AND c.").append(column).append("=?"); args.add(argument);
            }
            return new Filter(where.toString(), args.toArray());
        } catch (java.time.DateTimeException | NumberFormatException ex) { throw invalid("日期或筛选标识格式不正确"); }
    }

    @Scheduled(fixedDelayString = "${isd.ai.usage.stale-check-ms:60000}")
    public void markInterrupted() {
        // A late worker may still finish afterwards; its final metadata will replace this state.
        LocalDateTime cutoff = BusinessTime.now().minusMinutes(15);
        jdbc.update("""
                UPDATE ai_usage_call SET upstream_outcome='INTERRUPTED',usage_complete=0,estimated_cost=NULL,
                  business_outcome=CASE WHEN business_outcome='PENDING' THEN 'ERROR' ELSE business_outcome END
                WHERE upstream_outcome='PENDING' AND created_at<? LIMIT 1000
                """, cutoff);
    }

    @Scheduled(cron = "${isd.ai.usage.retention-cron:0 0 4 * * *}", zone = "Asia/Shanghai")
    public void cleanup() {
        LocalDateTime cutoff = BusinessTime.now().minusDays(365);
        for (int batch = 0; batch < 50; batch++) {
            if (jdbc.update("DELETE FROM ai_usage_call WHERE created_at<? ORDER BY created_at LIMIT 1000", cutoff) < 1000) break;
        }
    }

    private static int positive(String text, String name, int maximum) {
        try { int value = Integer.parseInt(text); if (value > 0 && value <= maximum) return value; }
        catch (NumberFormatException ignored) { }
        throw invalid(name + "超出有效范围");
    }
    private static long number(Map<String, Object> map, String key) { return ((Number) map.get(key)).longValue(); }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException { long value = rs.getLong(name); return rs.wasNull() ? null : value; }
    private static String truncate(String value, int length) { return value == null ? null : value.substring(0, Math.min(value.length(), length)); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAM, message); }
    private record Filter(String where, Object[] arguments) { }
}
