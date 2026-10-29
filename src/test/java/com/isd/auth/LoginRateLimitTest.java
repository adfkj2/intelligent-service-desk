package com.isd.auth;

import com.isd.auth.security.LoginAttemptGuard;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录防爆破（P0-3）。
 *
 * IP 维度的阈值在测试环境被放宽到 1000（见 AbstractIntegrationTest），因为所有用例都从
 * 127.0.0.1 发请求，按真实 IP 阈值会把用例串起来。IP 维度的逻辑用直接构造组件的方式验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginRateLimitTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("同账号连续失败 5 次后锁定：第 6 次即使密码正确也返回 429，且提示剩余分钟数")
    void locksAfterRepeatedFailures() throws Exception {
        register("rate_victim", "password123");

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TestSessions.loginBody("rate_victim", "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40100));
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody("rate_victim", "password123")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value(containsString("分钟")));

        // 计数落在 Redis：多实例共享，不会因为换实例就绕过
        assertThat(redis.hasKey("isd:login:fail:user:rate_victim")).isTrue();
    }

    @Test
    @DisplayName("登录成功会清空该账号的失败计数：不会被之前的失误拖累")
    void successResetsFailureCounter() throws Exception {
        register("rate_reset", "password123");

        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TestSessions.loginBody("rate_reset", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        // 4 次还没到阈值，正确密码可以登录，并清空计数
        TestSessions.login(mockMvc, "rate_reset", "password123");
        assertThat(redis.hasKey("isd:login:fail:user:rate_reset")).isFalse();

        // 计数被清空后，再错 4 次仍然不锁
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TestSessions.loginBody("rate_reset", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody("rate_reset", "password123")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IP 维度：同一来源累计失败达到阈值后，换账号也被挡；换来源 IP 不受影响")
    void ipThresholdIsIndependentOfUsername() {
        // 直接构造组件，用更小的阈值验证 IP 维度，不受测试环境放宽的配置影响
        LoginAttemptGuard guard = new LoginAttemptGuard(redis, 100, 2, 1);
        String attackerIp = "203.0.113.77";

        guard.recordFailure("victim-a", attackerIp);
        assertThat(guard.lockedSeconds("victim-b", attackerIp)).isZero();

        guard.recordFailure("victim-a", attackerIp);
        assertThat(guard.lockedSeconds("victim-b", attackerIp))
                .as("端口扫描式撞库：账号没到阈值，但同一 IP 已经锁了")
                .isBetween(1L, 60L);
        assertThat(guard.lockedSeconds("victim-b", "203.0.113.78"))
                .as("换来源 IP 不该受牵连")
                .isZero();
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody(username, password)))
                .andExpect(status().isCreated());
    }
}
