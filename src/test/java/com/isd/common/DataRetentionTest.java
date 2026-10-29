package com.isd.common;

import java.time.LocalDateTime;
import java.util.Objects;

import com.isd.common.retention.RetentionService;
import com.isd.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据保留策略：验证"只删该删的"。
 *
 * 保留天数刻意设成 3650 天、过期数据放 4000 天前：本地没有 Docker 时测试会连开发库，
 * 用小保留期会把开发库里的真实历史数据一起删掉。用"极老数据"做样本，
 * 边界只可能命中本用例自己插入的行。
 *
 * @Transactional 让删除随用例回滚，测试之间互不影响。
 */
@SpringBootTest(properties = {
        "isd.retention.batch-size=100",
        "isd.retention.max-batches=10",
        "isd.retention.ai-log-days=3650",
        "isd.retention.outbox-sent-days=3650",
        "isd.retention.notification-read-days=3650"
})
@Transactional
class DataRetentionTest extends AbstractIntegrationTest {

    /** 早于 3650 天的边界，一定会被清理 */
    private static final LocalDateTime EXPIRED = LocalDateTime.now().minusDays(4000);
    /** 一天前，一定不在清理范围内 */
    private static final LocalDateTime FRESH = LocalDateTime.now().minusDays(1);

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("AI 审计：只删超过保留期的记录，新记录保留")
    void deletesOnlyExpiredAiChatLogs() {
        insertAiLog("retention-old-1", EXPIRED);
        insertAiLog("retention-old-2", EXPIRED);
        insertAiLog("retention-new-1", FRESH);

        RetentionService.CleanupResult result = retentionService.cleanupAll();

        // 本地跑时开发库可能还有别的极老数据，所以断言 >= 而不是精确等于
        assertThat(result.aiChatLogs()).isGreaterThanOrEqualTo(2);
        assertThat(aiLogCount("retention-old-1")).isZero();
        assertThat(aiLogCount("retention-old-2")).isZero();
        assertThat(aiLogCount("retention-new-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("发件箱：只删过期的 SENT，PENDING/FAILED 与未过期事件保留")
    void deletesOnlyExpiredSentEvents() {
        insertOutbox(990011L, "SENT", EXPIRED);
        insertOutbox(990012L, "SENT", FRESH);
        insertOutbox(990013L, "PENDING", EXPIRED);
        insertOutbox(990014L, "FAILED", EXPIRED);

        RetentionService.CleanupResult result = retentionService.cleanupAll();

        assertThat(result.outboxEvents()).isGreaterThanOrEqualTo(1);
        assertThat(outboxCount(990011L)).as("过期且已投递：该删").isZero();
        assertThat(outboxCount(990012L)).as("未过期：保留").isEqualTo(1);
        assertThat(outboxCount(990013L)).as("PENDING 还要投递：不能删").isEqualTo(1);
        assertThat(outboxCount(990014L)).as("FAILED 是排查现场：不能删").isEqualTo(1);
    }

    @Test
    @DisplayName("站内信：只删过期的已读，未读与未过期保留")
    void deletesOnlyExpiredReadNotifications() {
        insertNotification(990021L, 1, EXPIRED);
        insertNotification(990022L, 0, EXPIRED);
        insertNotification(990023L, 1, FRESH);

        RetentionService.CleanupResult result = retentionService.cleanupAll();

        assertThat(result.notifications()).isGreaterThanOrEqualTo(1);
        assertThat(notificationCount(990021L)).as("过期且已读：该删").isZero();
        assertThat(notificationCount(990022L)).as("未读：用户可能还没看，不能删").isEqualTo(1);
        assertThat(notificationCount(990023L)).as("未过期：保留").isEqualTo(1);
    }

    private void insertAiLog(String traceId, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO ai_chat_log (trace_id, user_id, question, answer, source,
                                         kb_hits, tool_calls, duration_ms, success, created_at)
                VALUES (?, 990001, 'retention test', 'ok', 'LLM', 0, 0, 1, 1, ?)
                """, traceId, createdAt);
    }

    private int aiLogCount(String traceId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_log WHERE trace_id = ?", Integer.class, traceId));
    }

    private void insertOutbox(long aggregateId, String status, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox_event (event_type, aggregate_type, aggregate_id, payload, status, created_at)
                VALUES ('RETENTION_TEST', 'TEST', ?, '{}', ?, ?)
                """, aggregateId, status, createdAt);
    }

    private int outboxCount(long aggregateId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_type = 'TEST' AND aggregate_id = ?",
                Integer.class, aggregateId));
    }

    private void insertNotification(long userId, int readFlag, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO notification (user_id, ticket_id, type, title, content, read_flag, event_id, created_at)
                VALUES (?, NULL, 'RETENTION_TEST', 'retention test', 'retention test', ?, NULL, ?)
                """, userId, readFlag, createdAt);
    }

    private int notificationCount(long userId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'RETENTION_TEST'",
                Integer.class, userId));
    }
}
