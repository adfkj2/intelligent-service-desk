package com.isd.auth;

import com.jayway.jsonpath.JsonPath;
import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * “重置密码 → 临时密码登录 → 强制改密 → 正常使用”的完整闭环，以及公开联系方式接口。
 * 类级 @Transactional：用例结束后回滚，不污染演示数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("重置密码：旧密码失效、旧会话被踢、临时密码强制改密")
    void resetPasswordForcesChange() throws Exception {
        register("flow_victim", "password123");
        User victim = userMapper.findByUsername("flow_victim").orElseThrow();
        MockHttpSession oldSession = login("flow_victim", "password123");

        MvcResult reset = mockMvc.perform(post("/api/admin/users/{id}/password/reset", victim.getId())
                        .with(user(TestUsers.admin(7001L))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();

        String temporaryPassword = JsonPath.read(reset.getResponse().getContentAsString(),
                "$.data.temporaryPassword");
        assertThat(temporaryPassword)
                .hasSize(12)
                .doesNotContain("O", "0", "I", "l", "1");

        // 旧密码不能再登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("flow_victim", "password123")))
                .andExpect(status().isUnauthorized());

        // 重置前的旧会话被 SessionRegistry 标记过期，下一次请求 401
        mockMvc.perform(get("/api/auth/me").session(oldSession))
                .andExpect(status().isUnauthorized());

        // 临时密码可以登录，但被标记为必须改密
        MvcResult tempLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("flow_victim", temporaryPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        MockHttpSession tempSession = (MockHttpSession) tempLogin.getRequest().getSession(false);

        // 服务端拦截：未改密时业务接口 403
        mockMvc.perform(get("/api/tickets").session(tempSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));

        // 改密成功
        mockMvc.perform(post("/api/auth/change-password").session(tempSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"newPassword123"}
                                """.formatted(temporaryPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        // 同一个会话改密后立刻可以访问业务接口
        mockMvc.perform(get("/api/tickets").session(tempSession))
                .andExpect(status().isOk());

        // 新密码可登录，临时密码失效
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("flow_victim", "newPassword123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("flow_victim", temporaryPassword)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("管理员不能重置自己的密码：409")
    void adminCannotResetOwnPassword() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/password/reset", 7001L)
                        .with(user(TestUsers.admin(7001L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("改密时原密码错误：400，且不踢回登录页")
    void wrongOldPassword() throws Exception {
        register("flow_wrong", "password123");
        MockHttpSession session = login("flow_wrong", "password123");

        mockMvc.perform(post("/api/auth/change-password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"not-my-password","newPassword":"newPassword123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("公开联系方式：只返回启用且填写了联系方式的管理员")
    void publicAdminContacts() throws Exception {
        // 测试事务内先清空联系方式，保证断言不受演示数据影响（方法结束后回滚）。
        // 必须放在本方法第一条 SQL：之后所有 MyBatis 查询才能看到清空后的值。
        jdbcTemplate.update("UPDATE sys_user SET phone = NULL, email = NULL");

        // 启用中的管理员：通过接口保存联系方式
        register("contact_admin", "password123");
        User enabled = userMapper.findByUsername("contact_admin").orElseThrow();
        userMapper.updateRole(enabled.getId(), "ADMIN", enabled.getVersion());
        MockHttpSession session = login("contact_admin", "password123");

        mockMvc.perform(put("/api/auth/me/contact").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"13800001111","email":"helpdesk@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("13800001111"))
                .andExpect(jsonPath("$.data.email").value("helpdesk@example.com"));

        // 已禁用的管理员即使有联系方式也不应出现
        register("contact_disabled", "password123");
        User disabled = userMapper.findByUsername("contact_disabled").orElseThrow();
        userMapper.updateRole(disabled.getId(), "ADMIN", disabled.getVersion());
        disabled = userMapper.findById(disabled.getId()).orElseThrow();
        userMapper.updateContact(disabled.getId(), "13900002222", "disabled@example.com");
        userMapper.updateStatus(disabled.getId(), 0, disabled.getVersion());

        // 公开接口无需登录即可访问，且不暴露用户名
        mockMvc.perform(get("/api/auth/admin-contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fullName").value("contact_admin"))
                .andExpect(jsonPath("$.data[0].phone").value("13800001111"))
                .andExpect(jsonPath("$.data[0].email").value("helpdesk@example.com"))
                .andExpect(jsonPath("$.data[0].username").doesNotExist());
    }

    @Test
    @DisplayName("管理员清空联系方式：400")
    void adminContactCannotBeEmpty() throws Exception {
        register("contact_empty", "password123");
        User admin = userMapper.findByUsername("contact_empty").orElseThrow();
        // 用 Mapper 提权（而不是 JdbcTemplate），确保 MyBatis 一级缓存被清掉，Service 能读到 ADMIN
        userMapper.updateRole(admin.getId(), "ADMIN", admin.getVersion());
        MockHttpSession session = login("contact_empty", "password123");

        mockMvc.perform(put("/api/auth/me/contact").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"","email":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isCreated());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
