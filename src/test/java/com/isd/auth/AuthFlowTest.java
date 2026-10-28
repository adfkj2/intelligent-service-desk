package com.isd.auth;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与权限集成测试。
 * @Transactional 让注册的用户在用例结束后回滚；同一事务内 MockMvc 的后续请求能看到它，
 * 因为所有查询都跑在同一条数据库连接上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("注册 → 登录 → 查 me → 注销，会话在注销后失效")
    void registerLoginMeLogout() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123","fullName":"Alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"));

        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk());

        // SecurityContextLogoutHandler 会 invalidate session，所以这里断言 session 已失效
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("密码错误：401，且不泄露「用户是否存在」")
    void wrongPassword() throws Exception {
        register("bobby");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bobby","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"not-exist","password":"password123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("重复注册：409")
    void duplicateUsername() throws Exception {
        register("carol");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"carol","password":"password123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("注册参数校验：短密码 400")
    void weakPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"dave","password":"123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("被禁用的账号无法登录：403")
    void disabledUserCannotLogin() throws Exception {
        register("erin");
        User user = userMapper.findByUsername("erin").orElseThrow();
        userMapper.updateStatus(user.getId(), 0, user.getVersion());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"erin","password":"password123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("未登录访问受保护接口：401")
    void unauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("EMPLOYEE 访问管理端：403")
    void employeeCannotAccessAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(user(TestUsers.employee(1L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("ADMIN 可以查看用户列表并禁用他人，但不能禁用自己")
    void adminManagesUsers() throws Exception {
        register("frank");
        User frank = userMapper.findByUsername("frank").orElseThrow();
        long adminId = 7001L;

        mockMvc.perform(get("/api/admin/users").with(user(TestUsers.admin(adminId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/admin/users/{id}/status", frank.getId())
                        .with(user(TestUsers.admin(adminId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/admin/users/{id}/status", adminId)
                        .with(user(TestUsers.admin(adminId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":0}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ADMIN 可以把员工调整为技术支持，角色立即落库")
    void adminCanChangeRole() throws Exception {
        register("grace");
        User grace = userMapper.findByUsername("grace").orElseThrow();

        mockMvc.perform(patch("/api/admin/users/{id}/role", grace.getId())
                        .with(user(TestUsers.admin(7001L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"SUPPORT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPPORT"));

        assertThat(userMapper.findById(grace.getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.SUPPORT);

        // 另一个管理员重复设成 SUPPORT 属于幂等操作，不应该报 409
        mockMvc.perform(patch("/api/admin/users/{id}/role", grace.getId())
                        .with(user(TestUsers.admin(7002L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"SUPPORT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SUPPORT"));
    }

    @Test
    @DisplayName("至少保留一个启用管理员：降级最后一个 409")
    void lastEnabledAdminProtected() throws Exception {
        // 测试事务内的全局调整，方法结束后随事务回滚，不会影响真实数据
        jdbcTemplate.update("UPDATE sys_user SET role = 'EMPLOYEE'");

        User keeper = insertUser("role_keeper");
        User victim = insertUser("role_victim");
        jdbcTemplate.update("UPDATE sys_user SET role = 'ADMIN' WHERE id IN (?, ?)",
                keeper.getId(), victim.getId());

        // 两个管理员时，降级其中一个允许
        mockMvc.perform(patch("/api/admin/users/{id}/role", victim.getId())
                        .with(user(TestUsers.admin(keeper.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"EMPLOYEE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EMPLOYEE"));

        // 只剩 keeper 一个启用管理员，再降级必须拒绝
        mockMvc.perform(patch("/api/admin/users/{id}/role", keeper.getId())
                        .with(user(TestUsers.admin(victim.getId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"EMPLOYEE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message")
                        .value("系统至少需要保留一个启用状态的管理员，无法降级该账号"));
    }

    @Test
    @DisplayName("ADMIN 不能修改自己的角色：409")
    void adminCannotChangeOwnRole() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{id}/role", 7001L)
                        .with(user(TestUsers.admin(7001L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"EMPLOYEE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("非法角色：400")
    void invalidRoleRejected() throws Exception {
        register("heidi");
        User heidi = userMapper.findByUsername("heidi").orElseThrow();

        mockMvc.perform(patch("/api/admin/users/{id}/role", heidi.getId())
                        .with(user(TestUsers.admin(7001L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ROOT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("非 ADMIN 调整角色：403")
    void employeeCannotChangeRole() throws Exception {
        register("ivan");
        User ivan = userMapper.findByUsername("ivan").orElseThrow();

        mockMvc.perform(patch("/api/admin/users/{id}/role", ivan.getId())
                        .with(user(TestUsers.employee(1L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    private User insertUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("{noop}password123");
        user.setFullName(username);
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
    }
}
