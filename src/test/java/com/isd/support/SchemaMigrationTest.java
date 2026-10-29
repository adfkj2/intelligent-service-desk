package com.isd.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住"启动即建表"这条链路：Flyway 迁移必须真的执行过，关键字段不能悄悄丢。
 * 本地走本机库时版本记录是 BASELINE 类型，容器里是 SQL 类型，所以只断言版本与 success。
 */
@SpringBootTest
class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 已应用全部迁移：业务表、幂等键、全文索引、AI 审计表、发件箱、清理索引都在")
    void schemaIsMigrated() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6') AND success = 1",
                Integer.class);
        assertThat(applied).isEqualTo(6);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).contains(
                "sys_user", "ticket", "ticket_operation_log", "kb_category", "kb_item",
                "ai_chat_log", "outbox_event", "notification", "flyway_schema_history");

        List<String> userColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sys_user'",
                String.class);
        assertThat(userColumns).contains("phone", "email", "must_change_password", "version");

        // V2：幂等键列与唯一索引（踢掉它，重复提交就会双写）
        List<String> ticketColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'ticket'",
                String.class);
        assertThat(ticketColumns).contains("idempotency_key");
        Integer idempotencyIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'ticket' "
                        + "AND index_name = 'uk_creator_idempotency' AND non_unique = 0",
                Integer.class);
        assertThat(idempotencyIndex).isGreaterThan(0);

        // V3：全文索引（用 ngram 解析器，否则中文查询恒为空）
        Integer fulltextIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'kb_item' "
                        + "AND index_name = 'ft_kb_item' AND index_type = 'FULLTEXT'",
                Integer.class);
        assertThat(fulltextIndex).isGreaterThan(0);

        // V5：取批索引（status 前缀，避免调度器全表扫描）
        Integer outboxIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'outbox_event' "
                        + "AND index_name = 'idx_status_created'",
                Integer.class);
        assertThat(outboxIndex).isGreaterThan(0);

        // V5：站内信去重唯一键，去掉它调度器重试就会重复发（幂等的唯一防线）
        Integer notificationUniqueKey = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'notification' "
                        + "AND index_name = 'uk_event_user' AND non_unique = 0",
                Integer.class);
        assertThat(notificationUniqueKey).isGreaterThan(0);

        // V6：保留任务的删除索引（没有它，按时间清理会退化成全表扫描）
        Integer aiLogIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'ai_chat_log' "
                        + "AND index_name = 'idx_ai_log_created'",
                Integer.class);
        assertThat(aiLogIndex).isGreaterThan(0);

        Integer notificationCleanIndex = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'notification' "
                        + "AND index_name = 'idx_notification_read_created'",
                Integer.class);
        assertThat(notificationCleanIndex).isGreaterThan(0);
    }
}
