package com.isd.common;

import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 指标不是"看着有就行"：用真实请求驱动一次，断言计数器确实动了。
 * 计数一律用差值——Spring 上下文会复用，MeterRegistry 不会在测试之间清零。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MetricsApiTest extends AbstractIntegrationTest {

    private static final String EMPLOYEE = """
            {"username":"no_such_user_for_metrics","password":"wrong-password-123"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("登录失败与 URL 级越权都会各计一次数")
    void loginFailureAndForbiddenAreCounted() throws Exception {
        double loginBefore = counterValue("isd.auth.login", "result", "bad_credentials");
        double forbiddenBefore = counterValue("isd.auth.forbidden", "layer", "url");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPLOYEE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/users")
                        .with(user(TestUsers.employee(9801L))))
                .andExpect(status().isForbidden());

        assertThat(counterValue("isd.auth.login", "result", "bad_credentials")).isEqualTo(loginBefore + 1);
        assertThat(counterValue("isd.auth.forbidden", "layer", "url")).isEqualTo(forbiddenBefore + 1);
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }
}
