package com.isd.kb;

import com.isd.ai.dto.ChatAnswerResponse;
import com.isd.ai.service.AgentService;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "isd.ai.rate-limit.enabled=false")
@AutoConfigureMockMvc
@Transactional
class KnowledgeWorkflowTest extends AbstractIntegrationTest {
    private static final LoginUser SUPPORT = TestUsers.support(18101L);
    private static final LoginUser OTHER = TestUsers.support(18102L);
    private static final LoginUser EMPLOYEE = TestUsers.employee(18103L);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.apache.ibatis.session.SqlSession sqlSession;
    @Autowired com.isd.kb.service.KnowledgeWorkflowService workflow;
    @MockitoBean AgentService agent;

    @Test
    void reopenedDuringGenerationCannotCreateKnowledgeDraft() throws Exception {
        long ticket = solvedTicket();
        when(agent.chatWithContext(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            jdbc.update("UPDATE ticket SET status='IN_PROGRESS' WHERE id=?", ticket);
            return new ChatAnswerResponse("基础建议", "FALLBACK", List.of());
        });
        mvc.perform(post("/api/kb/drafts/from-ticket").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketId\":" + ticket + "}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_draft WHERE ticket_id=?", Long.class, ticket)).isZero();
        mvc.perform(post("/api/kb/drafts/from-ticket").with(user(EMPLOYEE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketId\":" + ticket + "}")).andExpect(status().isForbidden());
    }

    @Test
    void oldPendingDraftIsNotHiddenByPublishedDrafts() throws Exception {
        jdbc.update("INSERT INTO kb_draft(ticket_id,title,content,created_by,source) VALUES (1,'仍待审核','处理步骤',?,'FALLBACK')", SUPPORT.getId());
        for (int i = 0; i < 105; i++) {
            jdbc.update("INSERT INTO kb_draft(ticket_id,title,content,created_by,source,status) VALUES (1,'已发布','处理步骤',?,'FALLBACK','PUBLISHED')", SUPPORT.getId());
        }
        mvc.perform(get("/api/kb/drafts").with(user(SUPPORT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("仍待审核"));
    }

    @Test
    void malformedGeneratedContentFallsBackAndPastReviewCannotPublish() throws Exception {
        long ticket = solvedTicket();
        long category = category();
        when(agent.chatWithContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChatAnswerResponse("invalid json", "LLM", List.of()));
        String response = mvc.perform(post("/api/kb/drafts/from-ticket").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + ticket + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        jdbc.update("UPDATE kb_draft SET review_due_at='2000-01-01 00:00:00' WHERE id=?", id);
        mvc.perform(post("/api/kb/drafts/" + id + "/publish").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + category + ",\"version\":0}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_item WHERE source_ticket_id=?", Long.class, ticket)).isZero();
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentPublicationCreatesExactlyOneKnowledgeItem() throws Exception {
        long category = category();
        String title = "并发知识发布" + java.util.UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO kb_draft(ticket_id,title,content,created_by,source) VALUES (1,?,'已审核内容',?,'FALLBACK')", title, SUPPORT.getId());
        long id = jdbc.queryForObject("SELECT id FROM kb_draft WHERE title=?", Long.class, title);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Long> publish = () -> {
                start.await();
                return workflow.publish(id, new com.isd.kb.dto.KnowledgeWorkflow.Publish(category, 0), SUPPORT).id();
            };
            var first = pool.submit(publish);
            var second = pool.submit(publish);
            start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_item WHERE title=?", Long.class, title)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM kb_draft WHERE id=?", id);
            jdbc.update("DELETE FROM kb_item WHERE title=?", title);
            jdbc.update("DELETE FROM kb_category WHERE id=?", category);
        }
    }

    @Test
    void draftRequiresReviewAndPublicationIsIdempotent() throws Exception {
        long ticket = solvedTicket();
        when(agent.chatWithContext(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChatAnswerResponse("资料不足", "FALLBACK", List.of()));
        long category = category();
        String draftJson = mvc.perform(post("/api/kb/drafts/from-ticket").with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketId\":" + ticket + ",\"environment\":\"Windows 11\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(draftJson).path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_item WHERE source_ticket_id=?", Long.class, ticket)).isZero();
        mvc.perform(get("/api/kb/drafts").with(user(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/kb/drafts").with(user(OTHER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        String publish = "{\"categoryId\":" + category + ",\"version\":0}";
        mvc.perform(post("/api/kb/drafts/" + id + "/publish").with(user(OTHER))
                .contentType(MediaType.APPLICATION_JSON).content(publish)).andExpect(status().isNotFound());
        String published = mvc.perform(post("/api/kb/drafts/" + id + "/publish").with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON).content(publish)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.environment").value("Windows 11"))
                .andExpect(jsonPath("$.data.reviewedBy").value(SUPPORT.getId().intValue()))
                .andReturn().getResponse().getContentAsString();
        long item = json.readTree(published).path("data").path("id").asLong();
        mvc.perform(post("/api/kb/drafts/" + id + "/publish").with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON).content(publish)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value((int) item));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kb_item WHERE source_ticket_id=?", Long.class, ticket)).isEqualTo(1);
    }

    @Test
    void structuredDraftCanBeEditedButStaleVersionCannotPublish() throws Exception {
        long ticket = solvedTicket();
        long category = category();
        when(agent.chatWithContext(anyString(), anyString(), anyString(), anyString())).thenReturn(new ChatAnswerResponse(
                "{\"title\":\"VPN 连接排查\",\"content\":\"检查客户端版本；重新登录后验证连接。\",\"tags\":\"VPN\"}", "LLM", List.of()));
        String response = mvc.perform(post("/api/kb/drafts/from-ticket").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":" + ticket + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.source").value("LLM"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        String edit = "{\"title\":\"审核后的 VPN 排查\",\"content\":\"已核实的处理步骤\",\"version\":0}";
        mvc.perform(patch("/api/kb/drafts/" + id).with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(patch("/api/kb/drafts/" + id).with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/kb/drafts/" + id + "/publish").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\":" + category + ",\"version\":0}")).andExpect(status().isConflict());
        jdbc.update("UPDATE ticket SET status='IN_PROGRESS' WHERE id=?", ticket);
        sqlSession.clearCache();
        mvc.perform(post("/api/kb/drafts/from-ticket").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketId\":" + ticket + "}")).andExpect(status().isConflict());
    }

    @Test
    void knowledgeFeedbackIsPerUserAndReviewRequiresOwnership() throws Exception {
        long category = category();
        jdbc.update("INSERT INTO kb_item(category_id,title,content,created_by) VALUES (?,?,?,?)", category, "反馈条目", "内容", SUPPORT.getId());
        long item = jdbc.queryForObject("SELECT MAX(id) FROM kb_item WHERE category_id=?", Long.class, category);
        mvc.perform(post("/api/kb/items/" + item + "/feedback").with(user(EMPLOYEE)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"helpful\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.helpful").value(1));
        mvc.perform(post("/api/kb/items/" + item + "/feedback").with(user(EMPLOYEE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"helpful\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.helpful").value(0))
                .andExpect(jsonPath("$.data.unhelpful").value(1)).andExpect(jsonPath("$.data.myFeedback").value(false));
        String review = "{\"reviewDueAt\":\"2099-01-01T00:00:00\",\"environment\":\"Windows 11\"}";
        mvc.perform(post("/api/kb/items/" + item + "/review").with(user(OTHER)).contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/kb/items/" + item + "/review").with(user(SUPPORT)).contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.environment").value("Windows 11"));
        mvc.perform(post("/api/kb/items/" + item + "/feedback").with(user(EMPLOYEE)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    private long solvedTicket() {
        String no = "K" + java.util.UUID.randomUUID().toString().substring(0, 18);
        jdbc.update("INSERT INTO ticket(ticket_no,title,description,category,priority,status,creator_id,assignee_id) VALUES (?,?,?,?,?,?,?,?)",
                no, "VPN 连不上", "客户端登录失败", "IT_FAULT", "MEDIUM", "RESOLVED", EMPLOYEE.getId(), SUPPORT.getId());
        long id = jdbc.queryForObject("SELECT id FROM ticket WHERE ticket_no=?", Long.class, no);
        jdbc.update("INSERT INTO ticket_operation_log(ticket_id,operator_id,action,`comment`) VALUES (?,?,?,?)",
                id, SUPPORT.getId(), "RESOLVE", "更新 VPN 客户端并验证连接正常");
        return id;
    }
    private long category() {
        String name = "知识审核" + java.util.UUID.randomUUID().toString().substring(0, 10);
        jdbc.update("INSERT INTO kb_category(name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM kb_category WHERE name=?", Long.class, name);
    }
}
