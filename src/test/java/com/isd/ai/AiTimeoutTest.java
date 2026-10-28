package com.isd.ai;

import com.isd.ai.client.LlmClient;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 超时降级测试。
 * 单独一个类是因为要改 isd.ai.timeout-seconds，配置不同意味着另一个 Spring 上下文，
 * 混在 AiAgentTest 里会让所有用例都多等 1 秒。
 */
@SpringBootTest(properties = "isd.ai.timeout-seconds=1")
@AutoConfigureMockMvc
@Transactional
class AiTimeoutTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(9601L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    @DisplayName("模型响应超过 1 秒：按超时降级，而不是无限等待")
    void slowLlmTriggersTimeout() throws Exception {
        when(llmClient.chat(anyString())).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return "迟到的回答";
        });

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"我的工单进展"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.answer").value(containsString("超时")));
    }
}
