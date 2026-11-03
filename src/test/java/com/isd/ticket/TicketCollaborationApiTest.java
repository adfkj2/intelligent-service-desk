package com.isd.ticket;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import com.isd.ticket.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketCollaborationApiTest extends AbstractIntegrationTest {
    @Autowired private org.apache.ibatis.session.SqlSession sqlSession;
    private static final LoginUser EMPLOYEE=TestUsers.employee(97101L);
    private static final LoginUser OTHER=TestUsers.employee(97102L);
    private static final LoginUser SUPPORT=TestUsers.support(97201L);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TicketService tickets;
    @Autowired JdbcTemplate jdbc;

    private long create() throws Exception {
        String body=mvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"VPN 连接异常\",\"description\":\"客户端错误\",\"category\":\"IT_FAULT\",\"priority\":\"HIGH\",\"fillDurationSeconds\":45}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.responseDueAt").isNotEmpty())
                .andExpect(jsonPath("$.data.creatorName").isNotEmpty())
                .andExpect(jsonPath("$.data.fillDurationSeconds").value(45))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data").path("id").asLong();
    }

    private void action(long id,LoginUser operator,String action,String comment) throws Exception {
        mvc.perform(patch("/api/tickets/{id}/status",id).with(user(operator))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("action",action,"comment",comment))))
                .andExpect(status().isOk());
    }

    @Test
    void repliesAndInternalNotesStayWithinAuthorizedScope() throws Exception {
        long id=create();
        mvc.perform(post("/api/tickets/{id}/replies",id).with(user(SUPPORT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"请提供错误码\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").isNumber());
        mvc.perform(post("/api/tickets/{id}/replies",id).with(user(SUPPORT))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"内部网关配置待核对\",\"visibility\":\"INTERNAL\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}/replies",id).with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data",hasSize(1)))
                .andExpect(jsonPath("$.data[0].content").value("请提供错误码"));
        mvc.perform(get("/api/tickets/{id}/replies",id).with(user(SUPPORT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data",hasSize(2)));
        mvc.perform(get("/api/tickets/{id}/replies",id).with(user(OTHER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/operations",id).with(user(EMPLOYEE)))
                .andExpect(jsonPath("$.data[*].action",not(hasItem("INTERNAL_NOTE"))));
        mvc.perform(post("/api/tickets/{id}/replies",id).with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"test\",\"visibility\":\"INTERNAL\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void informationRequestResumesAndResolvedTicketCanBeReopened() throws Exception {
        long id=create();
        mvc.perform(post("/api/tickets/{id}/claim",id).with(user(SUPPORT))).andExpect(status().isOk());
        action(id,SUPPORT,"REQUEST_INFO","请提供错误码");
        mvc.perform(get("/api/tickets/overview").with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.informationRequests").value(1));
        mvc.perform(get("/api/tickets").with(user(EMPLOYEE)).param("queue","WAITING_ME"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].id",hasItem((int)id)));
        // Use the same application clock: container and host clocks can differ by fractions of a second.
        jdbc.update("UPDATE ticket SET waiting_since=? WHERE id=?",
                com.isd.common.time.BusinessTime.now().minusHours(2).minusSeconds(1),id);
        sqlSession.clearCache();
        action(id,EMPLOYEE,"PROVIDE_INFO","已补充");
        mvc.perform(get("/api/tickets/{id}",id).with(user(EMPLOYEE)))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.pausedSeconds",greaterThanOrEqualTo(7200)));
        action(id,SUPPORT,"RESOLVE","已更新客户端配置");
        action(id,EMPLOYEE,"REOPEN","仍无法连接");
        mvc.perform(get("/api/tickets/{id}",id).with(user(EMPLOYEE)))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.reopenCount").value(1))
                .andExpect(jsonPath("$.data.assigneeId").value(97201));
    }

    @Test
    void internalAttachmentIsFilteredAndCrossTicketDownloadRejected() throws Exception {
        long id=create();
        MockMultipartFile file=new MockMultipartFile("file","../截图.png","image/png",new byte[]{1,2,3});
        String body=mvc.perform(multipart("/api/tickets/{id}/attachments",id).file(file).param("visibility","INTERNAL")
                .with(user(SUPPORT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("截图.png"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long attachmentId=json.readTree(body).path("data").path("id").asLong();
        mvc.perform(get("/api/tickets/{id}/attachments",id).with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data",hasSize(0)));
        mvc.perform(get("/api/tickets/{id}/attachments/{attachmentId}",id,attachmentId).with(user(EMPLOYEE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/attachments/{attachmentId}",id,attachmentId).with(user(SUPPORT)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition",startsWith("attachment;")))
                .andExpect(content().bytes(new byte[]{1,2,3}));
        long another=create();
        mvc.perform(get("/api/tickets/{id}/attachments/{attachmentId}",another,attachmentId).with(user(SUPPORT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void overviewAndNewFiltersPreserveEmployeeScope() throws Exception {
        long id=create();
        mvc.perform(get("/api/tickets").with(user(OTHER)).param("priority","HIGH").param("queue","UNASSIGNED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items",hasSize(0)));
        mvc.perform(get("/api/tickets").with(user(EMPLOYEE)).param("priority","HIGH").param("queue","UNASSIGNED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[*].id",hasItem((int)id)));
        mvc.perform(get("/api/tickets/overview").with(user(EMPLOYEE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counts.OPEN").value(1))
                .andExpect(jsonPath("$.data.supportWorkloads",hasSize(0)))
                .andExpect(jsonPath("$.data.averageFillDurationSeconds").value(45.0));
        mvc.perform(get("/api/tickets/support-options").with(user(EMPLOYEE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets").with(user(EMPLOYEE)).param("queue","UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets").with(user(EMPLOYEE))
                .param("createdFrom","2026-11-03T00:00:00").param("createdTo","2026-11-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slaSchedulerWritesOneEventPerDeadlineAndStage() throws Exception {
        long id=create();
        mvc.perform(post("/api/tickets/{id}/claim",id).with(user(SUPPORT))).andExpect(status().isOk());
        jdbc.update("UPDATE ticket SET first_response_at=NOW(),resolution_due_at=DATE_ADD(NOW(),INTERVAL 20 MINUTE) WHERE id=?",id);
        sqlSession.clearCache();
        tickets.sendSlaReminders(); tickets.sendSlaReminders();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SLA_DUE_SOON'",Integer.class,id))
                .isEqualTo(1);
        jdbc.update("UPDATE ticket SET resolution_due_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",id);
        sqlSession.clearCache();
        tickets.sendSlaReminders(); tickets.sendSlaReminders();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SLA_BREACH'",Integer.class,id))
                .isEqualTo(1);
    }
}
