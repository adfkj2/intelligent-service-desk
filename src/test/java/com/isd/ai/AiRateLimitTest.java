package com.isd.ai;

import com.isd.ai.client.LlmClient;
import com.isd.ai.service.AiRateLimiter;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 配额限流。
 *
 * 用 properties 把单用户阈值压到 2，避免真去刷 20 次；
 * IP 维度不能靠 MockMvc 验证（所有用例同源 127.0.0.1，且测试环境已把 IP 阈值放到 1000），
 * 所以直接 new 一个更小阈值的限流器验证隔离性——与 LoginRateLimitTest 同一思路。
 *
 * Redis 不在事务里，`@BeforeEach`/`@AfterEach` 必须显式删键，否则用例之间会互相累加。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=sk-test-dummy",
        "isd.ai.rate-limit.max-per-user=2",
        "isd.ai.rate-limit.window-seconds=60"
})
@AutoConfigureMockMvc
@Transactional
class AiRateLimitTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(9601L);
    private static final String USER_KEY = "isd:ai:req:user:9601";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IsdMetrics metrics;

    @MockitoBean
    private LlmClient llmClient;

    @BeforeEach
    void resetQuota() {
        redis.delete(USER_KEY);
        when(llmClient.chat(anyString())).thenReturn("已收到，正在查询。");
    }

    @AfterEach
    void clearQuota() {
        redis.delete(USER_KEY);
    }

    @Test
    @DisplayName("用户维度：第 3 次超配额返回 429，提示剩余秒数，被拒请求不再调用模型")
    void rejectsWhenUserQuotaExceeded() throws Exception {
        chat().andExpect(status().isOk());
        chat().andExpect(status().isOk());

        chat().andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message", containsString("秒")));

        // 计数落在 Redis：多实例共享，换实例绕不过去
        assertThat(redis.hasKey(USER_KEY)).isTrue();
        // 第三次在 Controller 就被拦下，没有产生 token 费用
        verify(llmClient, times(2)).chat(anyString());
    }

    @Test
    @DisplayName("IP 维度：同一出口换账号也被挡，换 IP 不受牵连")
    void ipQuotaIsIndependentOfUser() {
        AiRateLimiter limiter = new AiRateLimiter(redis, metrics, true, 100, 2, 60);
        String sharedIp = "203.0.113.201";

        limiter.check(1L, sharedIp);
        limiter.check(2L, sharedIp);
        assertThatThrownBy(() -> limiter.check(3L, sharedIp))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        limiter.check(3L, "203.0.113.202");

        redis.delete("isd:ai:req:user:1");
        redis.delete("isd:ai:req:user:2");
        redis.delete("isd:ai:req:user:3");
        redis.delete("isd:ai:req:ip:" + sharedIp);
        redis.delete("isd:ai:req:ip:203.0.113.202");
    }

    @Test
    @DisplayName("关闭限流后直接放行，不写 Redis（压测与排障用）")
    void disabledLimiterPassesThrough() {
        AiRateLimiter limiter = new AiRateLimiter(redis, metrics, false, 1, 1, 60);

        limiter.check(1L, "203.0.113.210");
        limiter.check(1L, "203.0.113.210");

        assertThat(redis.hasKey("isd:ai:req:user:1")).isFalse();
    }

    private ResultActions chat() throws Exception {
        return mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"我的工单处理到哪了"}
                        """));
    }
}
