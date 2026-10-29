package com.isd.auth;

import jakarta.servlet.http.Cookie;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestSessions;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话外置到 Redis 之后，验证「踢人」真的作用在共享存储上，而不是进程内存。
 *
 * 这类断言必须直接看 SessionRepository：只看 HTTP 状态码的话，
 * 内存 SessionRegistry 标记过期也能让旧 Cookie 401，无法区分两种实现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionRedisTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisIndexedSessionRepository sessionRepository;

    @Test
    @DisplayName("会话存在 Redis：重置密码后该账号全部会话记录消失，旧 Cookie 一律 401")
    void resetPasswordDeletesAllSessionsFromRedis() throws Exception {
        register("redis_victim", "password123");
        User victim = userMapper.findByUsername("redis_victim").orElseThrow();

        // 同一账号登录两次，模拟两台设备
        Cookie phone = TestSessions.login(mockMvc, "redis_victim", "password123");
        Cookie laptop = TestSessions.login(mockMvc, "redis_victim", "password123");
        assertThat(sessionRepository.findByPrincipalName("redis_victim"))
                .as("两个会话都应该落在 Redis，并且能被 principal 索引查到")
                .hasSize(2);

        mockMvc.perform(post("/api/admin/users/{id}/password/reset", victim.getId())
                        .with(user(TestUsers.admin(7001L))))
                .andExpect(status().isOk());

        assertThat(sessionRepository.findByPrincipalName("redis_victim"))
                .as("重置密码后 Redis 里不应再有该账号的会话")
                .isEmpty();
        mockMvc.perform(get("/api/auth/me").cookie(phone)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").cookie(laptop)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("本人改密：当前会话保留并轮换 id，其他设备会话从 Redis 删除")
    void changePasswordKeepsOnlyCurrentSession() throws Exception {
        register("redis_owner", "password123");
        Cookie current = TestSessions.login(mockMvc, "redis_owner", "password123");
        Cookie other = TestSessions.login(mockMvc, "redis_owner", "password123");

        MvcResult changed = mockMvc.perform(post("/api/auth/change-password").cookie(current)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"password123","newPassword":"newPassword456"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        // 改密会轮换 session id，响应里下发的是新 Cookie
        Cookie renewed = TestSessions.cookieOf(changed);

        mockMvc.perform(get("/api/auth/me").cookie(renewed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("redis_owner"));
        mockMvc.perform(get("/api/auth/me").cookie(other))
                .andExpect(status().isUnauthorized());

        assertThat(sessionRepository.findByPrincipalName("redis_owner"))
                .as("只剩当前这一台设备的会话")
                .hasSize(1);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody(username, password)))
                .andExpect(status().isCreated());
    }
}
