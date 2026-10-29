package com.isd.notification;

import java.util.Objects;

import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站内信接口集成测试。
 *
 * 重点不是 CRUD 本身，而是三条安全/正确性约束：
 * 1) 只能看到自己的通知（查询条件由服务端注入，前端无从指定 userId）；
 * 2) 标记别人的通知返回 404（不暴露"存在但不属于你"）；
 * 3) 全部已读不能波及他人。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiTest extends AbstractIntegrationTest {

    /** 用大号段避开开发库里的真实用户，避免本地跑测试时互相串数据 */
    private static final LoginUser ME = TestUsers.employee(9001L);
    private static final LoginUser OTHER = TestUsers.employee(9002L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("未登录访问站内信：401，统一结构")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("列表只返回自己的通知，且不暴露 userId")
    void listOnlyOwnNotifications() throws Exception {
        insertNotification(ME.getId(), "工单状态变更", "你的工单已解决", 0);
        insertNotification(OTHER.getId(), "工单已指派给你", "别人的通知", 0);

        mockMvc.perform(get("/api/notifications").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("工单状态变更"))
                .andExpect(jsonPath("$.data[0].read").value(false))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());

        mockMvc.perform(get("/api/notifications/unread-count").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    @DisplayName("标记自己的通知已读：未读数减一")
    void markOwnNotificationRead() throws Exception {
        long id = insertNotification(ME.getId(), "工单已指派给你", "请及时处理", 0);

        mockMvc.perform(patch("/api/notifications/{id}/read", id).with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(readFlagOf(id)).isEqualTo(1);
        mockMvc.perform(get("/api/notifications/unread-count").with(user(ME)))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    @DisplayName("标记别人的通知：404，且对方记录仍未读")
    void markOthersNotificationReturnsNotFound() throws Exception {
        long id = insertNotification(OTHER.getId(), "工单状态变更", "别人的通知", 0);

        mockMvc.perform(patch("/api/notifications/{id}/read", id).with(user(ME)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // 关键：不能"返回 404 但实际改成功了"
        assertThat(readFlagOf(id)).isZero();
    }

    @Test
    @DisplayName("全部已读：只影响自己，返回实际更新条数")
    void markAllReadOnlyAffectsSelf() throws Exception {
        insertNotification(ME.getId(), "工单状态变更", "a", 0);
        insertNotification(ME.getId(), "工单已指派给你", "b", 0);
        long othersId = insertNotification(OTHER.getId(), "工单状态变更", "c", 0);

        mockMvc.perform(post("/api/notifications/read-all").with(user(ME)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));

        mockMvc.perform(get("/api/notifications/unread-count").with(user(ME)))
                .andExpect(jsonPath("$.data.count").value(0));
        assertThat(readFlagOf(othersId)).isZero();
    }

    /**
     * 直接落库造数据：通知由调度器异步产生，接口测试不该依赖那条链路（它有自己的测试）。
     * event_id 留空——唯一索引 uk_event_user 对 NULL 不去重，正好允许这里反复插入。
     */
    private long insertNotification(Long userId, String title, String content, int readFlag) {
        jdbcTemplate.update("""
                INSERT INTO notification (user_id, ticket_id, type, title, content, read_flag, event_id)
                VALUES (?, NULL, 'TICKET_STATUS_CHANGED', ?, ?, ?, NULL)
                """, userId, title, content, readFlag);
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM notification WHERE user_id = ? AND title = ?", Long.class, userId, title),
                "通知未落库");
    }

    private int readFlagOf(long id) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT read_flag FROM notification WHERE id = ?", Integer.class, id), "通知不存在");
    }
}
