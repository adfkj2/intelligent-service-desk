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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 没配 API Key 时的降级路径。
 *
 * 为什么要单独一个类：需要覆盖 api-key，配置不同就是另一个 Spring 上下文，
 * 混进 AiAgentTest 会让那 5 个用例都跑在"未配置"分支下。
 *
 * 这条分支以前是"碰运气覆盖"——只有开发机没填真实 Key 时才走到，CI 上反而必挂；
 * 现在用与 application.yml 默认值一致的占位符固定下来（Spring AI 不接受空 Key，
 * 所以不能把属性置空；这个取值与 AgentService 里的 PLACEHOLDER_KEY 是同一约定）。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=local-placeholder")
@AutoConfigureMockMvc
class AiNotConfiguredTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(9701L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    @DisplayName("未配置 Key：直接降级，且完全不发起模型调用")
    void missingKeyFallsBackWithoutCallingLlm() throws Exception {
        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"打印机连不上"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.answer").value(containsString("AI 未配置 API Key")));

        // 关键断言：没有 Key 就不该有任何出网尝试，而不是调用了再靠异常兜底
        verifyNoInteractions(llmClient);
    }
}
