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
 * AI 接口测试：用桩替换 LlmClient，不依赖网络和真实 Key。
 * 这里验证的是"编排"（鉴权、参数校验、异常降级），不是模型答得好不好——
 * 模型回答质量只能靠人工评估，写进单元测试只会变成脆弱用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiAgentTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(9501L);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    @Test
    @DisplayName("模型正常返回：source=LLM，原文透传")
    void chatReturnsLlmAnswer() throws Exception {
        when(llmClient.chat(anyString())).thenReturn("你有 1 个工单处于处理中，处理人是技术支持。");

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"我的工单处理到哪了"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("LLM"))
                .andExpect(jsonPath("$.data.answer").value(containsString("处理中")))
                .andExpect(jsonPath("$.data.relatedTitles").isArray());
    }

    @Test
    @DisplayName("模型异常：仍然 200，source=FALLBACK，业务不被 AI 拖垮")
    void llmFailureFallsBack() throws Exception {
        when(llmClient.chat(anyString())).thenThrow(new IllegalStateException("upstream 502"));

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"打印机连不上怎么办"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.answer").value(containsString("AI 服务暂时不可用")));
    }

    @Test
    @DisplayName("模型返回空串：也走降级，不把空回答丢给用户")
    void emptyAnswerFallsBack() throws Exception {
        when(llmClient.chat(anyString())).thenReturn("   ");

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"VPN 怎么配置"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("FALLBACK"));
    }

    @Test
    @DisplayName("空白问题：400")
    void blankQuestion() throws Exception {
        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("未登录：401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"我的工单"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
