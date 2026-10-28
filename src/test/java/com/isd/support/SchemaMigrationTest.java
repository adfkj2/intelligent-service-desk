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
    @DisplayName("Flyway 已应用 V1：5 张业务表与 sys_user 关键字段齐全")
    void schemaIsMigrated() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class);
        assertThat(applied).isEqualTo(1);

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).contains(
                "sys_user", "ticket", "ticket_operation_log", "kb_category", "kb_item", "flyway_schema_history");

        List<String> userColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'sys_user'",
                String.class);
        assertThat(userColumns).contains("phone", "email", "must_change_password", "version");
    }
}
