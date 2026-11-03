package com.isd.ai;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.ai.client.LlmClient;
import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.entity.AiConversation;
import com.isd.ai.service.AiConversationStore;
import com.isd.ai.mapper.AiFeedbackMapper;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.SqlSession;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression scenarios for useful suggestions, ownership, privacy and confirmation boundaries. */
@SpringBootTest(properties = {"spring.ai.openai.api-key=sk-test-dummy", "isd.ai.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class AiWorkspaceApiTest extends AbstractIntegrationTest {
    private static final LoginUser EMPLOYEE = TestUsers.employee(9811L);
    private static final LoginUser OTHER = TestUsers.employee(9812L);
    private static final LoginUser SUPPORT = TestUsers.support(9813L);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiConversationStore store;
    @Autowired private SqlSession sqlSession;
    @Autowired private AiFeedbackMapper feedbackMapper;
    @MockitoBean private LlmClient llm;

    @Test
    void followsUpWithOwnedHistoryAndKeepsTicketsUnchanged() throws Exception {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chat(prompt.capture())).thenReturn("请记录 VPN 错误码。");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class);
        JsonNode first = data(send("/api/ai/chat", EMPLOYEE, "{\"question\":\"VPN 连不上\"}"));
        String id = first.path("conversationId").asText();
        assertThat(id).hasSize(36);
        assertThat(first.path("answerId").asLong()).isPositive();
        send("/api/ai/chat", EMPLOYEE, json.writeValueAsString(java.util.Map.of("question", "下一步呢？", "conversationId", id)))
                .andExpect(status().isOk());
        assertThat(prompt.getValue()).contains("VPN 连不上", "下一步呢？", "员工：", "助手：");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(before);
        mvc.perform(get("/api/ai/conversations/{id}", id).with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messages.length()").value(4));
        mvc.perform(get("/api/ai/conversations/{id}", id).with(user(OTHER))).andExpect(status().isNotFound());
        send("/api/ai/chat", OTHER, "{\"question\":\"继续\",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isNotFound());
        send("/api/ai/draft", EMPLOYEE, "{\"description\":\"继续\",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void ticketContextUsesPublicRepliesAndCannotBeSwitchedOrReadByOthers() throws Exception {
        long ticketId = createTicket(EMPLOYEE, "VPN断线");
        long another = createTicket(EMPLOYEE, "打印机故障");
        jdbc.update("INSERT INTO ticket_reply(ticket_id,author_id,content,visibility) VALUES(?,?,?,?)", ticketId, SUPPORT.getId(), "请提供错误619的发生时间", "PUBLIC");
        jdbc.update("INSERT INTO ticket_reply(ticket_id,author_id,content,visibility) VALUES(?,?,?,?)", ticketId, SUPPORT.getId(), "内部备注秘密工号 secret-9811", "INTERNAL");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chat(prompt.capture())).thenReturn("请补充错误提示。");
        JsonNode first = data(send("/api/ai/chat", EMPLOYEE, "{\"question\":\"分析这张单\",\"ticketId\":" + ticketId + "}"));
        assertThat(prompt.getValue()).contains("VPN断线", "请提供错误619的发生时间").doesNotContain("secret-9811");
        String id = first.path("conversationId").asText();
        send("/api/ai/chat", EMPLOYEE, "{\"question\":\"再看看\",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isOk());
        send("/api/ai/chat", EMPLOYEE, "{\"question\":\"换一单\",\"ticketId\":" + another + ",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isConflict());
        send("/api/ai/chat", OTHER, "{\"question\":\"分析这张单\",\"ticketId\":" + ticketId + "}")
                .andExpect(status().isNotFound());
        // Binding is checked again when history is loaded, rather than trusting initial authorization.
        jdbc.update("UPDATE ticket SET creator_id=? WHERE id=?", OTHER.getId(), ticketId);
        // JDBC fixture changes bypass MyBatis' transaction-local first-level cache.
        sqlSession.clearCache();
        mvc.perform(get("/api/ai/conversations/{id}", id).with(user(EMPLOYEE))).andExpect(status().isNotFound());
        send("/api/ai/chat", EMPLOYEE, "{\"question\":\"继续\",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void draftAsksForMissingFactsAcceptsFollowupAndDoesNotSubmit() throws Exception {
        when(llm.chat(anyString())).thenReturn("""
                ```json
                {"title":"VPN无法连接","description":"VPN连不上","category":"IT_FAULT","priority":"MEDIUM","missingInformation":[],"steps":["确认网络连接并记录错误提示"]}
                ```
                """);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class);
        JsonNode draft = data(send("/api/ai/draft", EMPLOYEE, "{\"description\":\"VPN 连不上\"}"));
        assertThat(draft.path("source").asText()).isEqualTo("LLM");
        assertThat(draft.path("missingInformation").size()).isEqualTo(4);
        String conversationId = draft.path("conversationId").asText();
        when(llm.chat(anyString())).thenReturn("invalid json");
        JsonNode followup = data(send("/api/ai/draft", EMPLOYEE,
                "{\"description\":\"今天上午9点开始，影响3人，显示错误619，重启无效\",\"conversationId\":\"" + conversationId + "\"}"));
        assertThat(followup.path("source").asText()).isEqualTo("FALLBACK");
        assertThat(followup.path("description").asText()).contains("VPN 连不上", "错误619");
        assertThat(followup.path("missingInformation").size()).isZero();
        assertThat(followup.path("steps").size()).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT source FROM ai_chat_log WHERE id=?", String.class, followup.path("answerId").asLong())).isEqualTo("FALLBACK");
    }

    @Test
    void malformedStructuredOutputSafelyFallsBackAndValidatesInput() throws Exception {
        for (String invalid : List.of("{}", "[]", "null", "```invalid```",
                "{\"title\":\"x\",\"description\":\"x\",\"category\":\"INVALID\",\"priority\":\"MEDIUM\",\"missingInformation\":[],\"steps\":[]}",
                "{\"title\":\"x\",\"description\":\"x\",\"category\":\"IT_FAULT\",\"priority\":\"MEDIUM\",\"missingInformation\":[42],\"steps\":[]}")) {
            when(llm.chat(anyString())).thenReturn(invalid);
            send("/api/ai/draft", EMPLOYEE, "{\"description\":\"打印机离线\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"))
                    .andExpect(jsonPath("$.data.category").value("DEVICE"));
        }
        send("/api/ai/draft", EMPLOYEE, "{\"description\":\" \"}").andExpect(status().isBadRequest());
        send("/api/ai/draft", EMPLOYEE, "{\"description\":\"申请账号权限\",\"conversationId\":\"bad\"}").andExpect(status().isBadRequest());
        send("/api/ai/chat", EMPLOYEE, "{\"question\":\"继续\",\"conversationId\":\"bad\"}").andExpect(status().isBadRequest());
    }

    @Test
    void modelFailureStillProducesUsefulEditablePermissionDraft() throws Exception {
        when(llm.chat(anyString())).thenThrow(new IllegalStateException("upstream unavailable"));
        send("/api/ai/draft", EMPLOYEE, "{\"description\":\"申请财务系统查看权限\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.category").value("PERMISSION"))
                .andExpect(jsonPath("$.data.priority").value("MEDIUM"));
    }

    @Test
    void longDraftFactsKeepAuditableAnswerAndAllowFeedback() throws Exception {
        when(llm.chat(anyString())).thenReturn("not-json");
        JsonNode draft = data(send("/api/ai/draft", EMPLOYEE,
                json.writeValueAsString(java.util.Map.of("description", "VPN无法连接。".repeat(100)))));
        long answerId = draft.path("answerId").asLong();
        assertThat(answerId).isPositive();
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(question) FROM ai_chat_log WHERE id=?", Integer.class, answerId)).isEqualTo(500);
        send("/api/ai/answers/" + answerId + "/feedback", EMPLOYEE, "{\"adopted\":true}").andExpect(status().isOk());
    }

    @Test
    void feedbackIsOwnedIdempotentAndAdoptionDoesNotOverwriteRating() throws Exception {
        when(llm.chat(anyString())).thenReturn("请检查网络。");
        long id = data(send("/api/ai/chat", EMPLOYEE, "{\"question\":\"VPN连不上\"}")).path("answerId").asLong();
        send("/api/ai/answers/" + id + "/feedback", EMPLOYEE, "{\"helpful\":true,\"resolved\":false}").andExpect(status().isOk());
        send("/api/ai/answers/" + id + "/feedback", EMPLOYEE, "{\"adopted\":true}").andExpect(status().isOk());
        send("/api/ai/answers/" + id + "/feedback", EMPLOYEE, "{\"adopted\":true}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_answer_feedback WHERE answer_id=?", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT helpful FROM ai_answer_feedback WHERE answer_id=?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT resolved FROM ai_answer_feedback WHERE answer_id=?", Boolean.class, id)).isFalse();
        send("/api/ai/answers/" + id + "/feedback", OTHER, "{\"helpful\":true}").andExpect(status().isNotFound());
        send("/api/ai/answers/" + id + "/feedback", EMPLOYEE, "{}").andExpect(status().isBadRequest());
        send("/api/ai/answers/999999999/feedback", EMPLOYEE, "{\"helpful\":true}").andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/metrics").with(user(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/metrics").with(user(SUPPORT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feedbackCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.adoptedCount").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void roleChangesInvalidatePreviouslyBroaderConversationHistory() throws Exception {
        when(llm.chat(anyString())).thenReturn("公司有多个工单等待处理。");
        String id = data(send("/api/ai/chat", SUPPORT, "{\"question\":\"有哪些工单\"}")).path("conversationId").asText();
        LoginUser demoted = TestUsers.employee(SUPPORT.getId());
        mvc.perform(get("/api/ai/conversations/{id}", id).with(user(demoted))).andExpect(status().isNotFound());
        send("/api/ai/chat", demoted, "{\"question\":\"继续\",\"conversationId\":\"" + id + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void suggestionAndHelpfulDenominatorsExcludePlainChatAndUnratedAdoptions() throws Exception {
        JsonNode before = metricData();
        when(llm.chat(anyString())).thenReturn("not-json");
        long chatId = data(send("/api/ai/chat", EMPLOYEE, "{\"question\":\"VPN怎么检查\"}")).path("answerId").asLong();
        long draftId = data(send("/api/ai/draft", EMPLOYEE, "{\"description\":\"VPN连不上\"}")).path("answerId").asLong();
        long ticketId = createTicket(EMPLOYEE, "VPN无法连接");
        long assistId = data(send("/api/ai/tickets/" + ticketId + "/assist", SUPPORT, "{}")).path("answerId").asLong();
        assertThat(jdbc.queryForObject("SELECT purpose FROM ai_chat_log WHERE id=?", String.class, chatId)).isEqualTo("CHAT");
        assertThat(jdbc.queryForObject("SELECT purpose FROM ai_chat_log WHERE id=?", String.class, draftId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT purpose FROM ai_chat_log WHERE id=?", String.class, assistId)).isEqualTo("ASSIST");
        assertThat(feedbackMapper.markSuggestion(chatId, OTHER.getId(), "DRAFT")).isZero();
        assertThat(feedbackMapper.markSuggestion(chatId, EMPLOYEE.getId(), "MODEL_GUESS")).isZero();

        send("/api/ai/answers/" + draftId + "/feedback", EMPLOYEE, "{\"adopted\":true}").andExpect(status().isOk());
        send("/api/ai/answers/" + assistId + "/feedback", SUPPORT, "{\"adopted\":true,\"helpful\":false}").andExpect(status().isOk());
        send("/api/ai/answers/" + chatId + "/feedback", EMPLOYEE, "{\"adopted\":true,\"helpful\":true}").andExpect(status().isOk());
        JsonNode after = metricData();
        assertThat(after.path("answers").asLong()).isEqualTo(before.path("answers").asLong() + 3);
        assertThat(after.path("suggestionCount").asLong()).isEqualTo(before.path("suggestionCount").asLong() + 2);
        assertThat(after.path("feedbackCount").asLong()).isEqualTo(before.path("feedbackCount").asLong() + 3);
        assertThat(after.path("helpfulFeedbackCount").asLong()).isEqualTo(before.path("helpfulFeedbackCount").asLong() + 2);
        assertThat(after.path("helpfulCount").asLong()).isEqualTo(before.path("helpfulCount").asLong() + 1);
        assertThat(after.path("adoptedCount").asLong()).isEqualTo(before.path("adoptedCount").asLong() + 3);
        assertThat(after.path("suggestionAdoptedCount").asLong()).isEqualTo(before.path("suggestionAdoptedCount").asLong() + 2);
        // A later rating adds one vote; repeated adoption remains a single suggestion adoption.
        send("/api/ai/answers/" + draftId + "/feedback", EMPLOYEE, "{\"adopted\":true,\"helpful\":false}").andExpect(status().isOk());
        JsonNode rated = metricData();
        assertThat(rated.path("helpfulFeedbackCount").asLong()).isEqualTo(after.path("helpfulFeedbackCount").asLong() + 1);
        assertThat(rated.path("suggestionAdoptedCount").asLong()).isEqualTo(after.path("suggestionAdoptedCount").asLong());
    }

    @Test
    void historyIsBoundedAndConcurrentAppendCannotInterleaveTurns() throws Exception {
        when(llm.chat(anyString())).thenReturn("建议记录错误码。");
        String id = data(send("/api/ai/chat", EMPLOYEE, "{\"question\":\"第0轮VPN问题\"}")).path("conversationId").asText();
        for (int turn = 1; turn <= 6; turn++) {
            send("/api/ai/chat", EMPLOYEE, "{\"question\":\"第" + turn + "轮继续\",\"conversationId\":\"" + id + "\"}")
                    .andExpect(status().isOk());
        }
        JsonNode loaded = json.readTree(mvc.perform(get("/api/ai/conversations/{id}", id).with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertThat(loaded.path("messages").size()).isEqualTo(10);
        assertThat(loaded.toString()).doesNotContain("第0轮VPN问题");
        AiConversation snapshot = store.owned(id, EMPLOYEE.getId());
        assertThatThrownBy(() -> store.append(snapshot, "消息", new ChatAnswerResponse(null, "LLM", List.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能为空");
        store.append(snapshot, "并发A", new ChatAnswerResponse("答复A", "LLM", List.of()));
        assertThatThrownBy(() -> store.append(snapshot, "并发B", new ChatAnswerResponse("答复B", "LLM", List.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("新的回答");
    }

    @Test
    void staffAssistReturnsGroundedSimilarCasesAndDoesNotPublishReply() throws Exception {
        long current = createTicket(EMPLOYEE, "VPN无法连接");
        long similar = createTicket(OTHER, "VPN无法连接错误619");
        mvc.perform(post("/api/tickets/{id}/claim", similar).with(user(SUPPORT))).andExpect(status().isOk());
        mvc.perform(patch("/api/tickets/{id}/status", similar).with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"RESOLVE\",\"comment\":\"已更新VPN客户端配置\"}")).andExpect(status().isOk());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chat(prompt.capture())).thenReturn("""
                {"summary":"员工报告VPN连接失败，原因待确认","steps":["核对错误码与客户端配置"],"replyDraft":"请补充客户端错误提示，便于继续排查。"}
                """);
        send("/api/ai/tickets/" + current + "/assist", EMPLOYEE, "{}").andExpect(status().isForbidden());
        send("/api/ai/tickets/" + current + "/assist", SUPPORT, "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("LLM"))
                .andExpect(jsonPath("$.data.similarTickets[0].id").value(similar));
        assertThat(prompt.getValue()).contains("已更新VPN客户端配置", "原因", "只输出 JSON");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reply WHERE ticket_id=?", Long.class, current)).isZero();
        when(llm.chat(anyString())).thenReturn("not-json");
        send("/api/ai/tickets/" + current + "/assist", SUPPORT, "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"));
    }

    private long createTicket(LoginUser creator, String title) throws Exception {
        return data(send("/api/tickets", creator, json.writeValueAsString(java.util.Map.of(
                "title", title, "description", "出差期间无法连接内网", "category", "IT_FAULT", "priority", "MEDIUM"))))
                .path("id").asLong();
    }

    private ResultActions send(String path, LoginUser actor, String body) throws Exception {
        return mvc.perform(post(path).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode metricData() throws Exception {
        return json.readTree(mvc.perform(get("/api/ai/metrics").with(user(SUPPORT))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
}
