package com.isd.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestSessions;
import com.isd.support.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No test transaction: access changes must actually commit before the Redis callback runs. */
@SpringBootTest(properties={"spring.ai.openai.api-key=local-placeholder","isd.ai.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class AccessChangeSessionTest extends AbstractIntegrationTest {
    private static final String PREFIX="access_change_";
    private static final String PASSWORD="password123";
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired RedisIndexedSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void before() { cleanup(); }
    @AfterEach
    void after() { cleanup(); }

    @Test
    void roleChangeRevokesOldCookiesAndNewRoleCannotReadStaffConversation() throws Exception {
        User support=register("staff",true);
        Cookie phone=TestSessions.login(mvc,support.getUsername(),PASSWORD);
        Cookie laptop=TestSessions.login(mvc,support.getUsername(),PASSWORD);
        String body=mvc.perform(post("/api/ai/chat").cookie(phone).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"打印机无法连接，如何排查？\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String conversationId=json.readTree(body).path("data").path("conversationId").asText();
        assertThat(conversationId).isNotBlank();
        assertThat(sessions.findByPrincipalName(support.getUsername())).hasSize(2);

        mvc.perform(patch("/api/admin/users/{id}/role",support.getId()).with(user(TestUsers.admin(98101L)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));

        assertThat(sessions.findByPrincipalName(support.getUsername())).isEmpty();
        mvc.perform(get("/api/auth/me").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(laptop)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/conversations/{id}",conversationId).cookie(phone))
                .andExpect(status().isUnauthorized());

        Cookie renewed=TestSessions.login(mvc,support.getUsername(),PASSWORD);
        mvc.perform(get("/api/auth/me").cookie(renewed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("EMPLOYEE"));
        mvc.perform(get("/api/ai/conversations/{id}",conversationId).cookie(renewed))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/chat").cookie(renewed).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("question","继续分析","conversationId",conversationId))))
                .andExpect(status().isNotFound());
    }

    @Test
    void disablingAccountRevokesAllCookiesAfterCommit() throws Exception {
        User employee=register("disabled",false);
        Cookie first=TestSessions.login(mvc,employee.getUsername(),PASSWORD);
        Cookie second=TestSessions.login(mvc,employee.getUsername(),PASSWORD);
        mvc.perform(patch("/api/admin/users/{id}/status",employee.getId()).with(user(TestUsers.admin(98101L)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(status().isOk());
        assertThat(sessions.findByPrincipalName(employee.getUsername())).isEmpty();
        mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody(employee.getUsername(),PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unchangedRoleIsIdempotentAndKeepsExistingSession() throws Exception {
        User support=register("same_role",true);
        Cookie existing=TestSessions.login(mvc,support.getUsername(),PASSWORD);
        mvc.perform(patch("/api/admin/users/{id}/role",support.getId()).with(user(TestUsers.admin(98101L)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPPORT\"}"))
                .andExpect(status().isOk());
        assertThat(sessions.findByPrincipalName(support.getUsername())).hasSize(1);
        mvc.perform(get("/api/auth/me").cookie(existing)).andExpect(status().isOk());
    }

    private User register(String suffix,boolean support) throws Exception {
        String name=PREFIX+suffix;
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody(name,PASSWORD)))
                .andExpect(status().isCreated());
        User result=users.findByUsername(name).orElseThrow();
        if (support) {
            users.updateRole(result.getId(),UserRole.SUPPORT.name(),result.getVersion());
            result=users.findById(result.getId()).orElseThrow();
        }
        return result;
    }

    private void cleanup() {
        List<User> affected=users.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).toList();
        for (User user:affected) {
            sessions.findByPrincipalName(user.getUsername()).values().forEach(s -> sessions.deleteById(s.getId()));
            jdbc.update("DELETE FROM ai_conversation WHERE user_id=?",user.getId());
            jdbc.update("DELETE FROM ai_chat_log WHERE user_id=?",user.getId());
            jdbc.update("DELETE FROM sys_user WHERE id=?",user.getId());
        }
    }
}
