package com.isd.ticket;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单全流程与越权测试：状态机、审计日志、资源级权限。
 * 每个用例都同时覆盖"允许"与"拒绝"两条路径，只测成功路径等于没测。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketWorkflowTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(2001L);
    private static final LoginUser OTHER_EMPLOYEE = TestUsers.employee(2002L);
    private static final LoginUser SUPPORT = TestUsers.support(3001L);
    private static final LoginUser OTHER_SUPPORT = TestUsers.support(3002L);
    private static final LoginUser ADMIN = TestUsers.admin(4001L);

    private static final String CREATE_BODY = """
            {"title":"VPN 连不上","description":"出差期间无法连接内网","category":"IT_FAULT","priority":"HIGH"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("完整流程：创建 → 接单 → 解决 → 关闭，每一步都写入审计日志")
    void fullLifecycle() throws Exception {
        long id = createTicket(EMPLOYEE);

        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.assigneeId").value(3001));

        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RESOLVE","comment":"已重置客户端配置"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.assigneeId").value(3001));

        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"CLOSE","comment":"问题已解决"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        mockMvc.perform(get("/api/tickets/{id}/operations", id).with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[1].action").value("CLAIM"))
                .andExpect(jsonPath("$.data[2].action").value("RESOLVE"))
                .andExpect(jsonPath("$.data[3].action").value("CLOSE"))
                .andExpect(jsonPath("$.data[3].fromStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data[3].toStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data[3].comment").value("问题已解决"));
    }

    @Test
    @DisplayName("非法迁移：OPEN 直接关闭 → 409")
    void illegalTransition() throws Exception {
        long id = createTicket(EMPLOYEE);

        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"CLOSE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    @DisplayName("重复接单：第二个人接单 → 409")
    void doubleClaim() throws Exception {
        long id = createTicket(EMPLOYEE);

        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(SUPPORT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(OTHER_SUPPORT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("不允许接单")));
    }

    @Test
    @DisplayName("角色越界：EMPLOYEE 接单 → 403")
    void claimRequiresSupportRole() throws Exception {
        long id = createTicket(EMPLOYEE);

        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(EMPLOYEE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("处理人越界：非当前处理人标记解决 → 403")
    void resolveByNonAssignee() throws Exception {
        long id = createTicket(EMPLOYEE);
        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(SUPPORT)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(OTHER_SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RESOLVE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("闭环权限：SUPPORT 不能关闭工单 → 403")
    void closeBySupport() throws Exception {
        long id = createTicket(EMPLOYEE);
        claimAndResolve(id);

        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"CLOSE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("资源级权限：别的员工看不到我的工单（404 防探测）")
    void otherEmployeeCannotRead() throws Exception {
        long id = createTicket(EMPLOYEE);

        mockMvc.perform(get("/api/tickets/{id}", id).with(user(OTHER_EMPLOYEE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(get("/api/tickets/{id}/operations", id).with(user(OTHER_EMPLOYEE)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("列表隔离：EMPLOYEE 只能看到自己创建的工单")
    void employeeListOnlyOwn() throws Exception {
        createTicket(EMPLOYEE);
        createTicket(EMPLOYEE);
        createTicket(OTHER_EMPLOYEE);

        mockMvc.perform(get("/api/tickets").param("size", "100").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.items[*].creatorId", Matchers.everyItem(Matchers.is(2001))));
    }

    @Test
    @DisplayName("分页：size=1 时只返回 1 条，totalPages 正确")
    void pagination() throws Exception {
        createTicket(EMPLOYEE);
        createTicket(EMPLOYEE);

        mockMvc.perform(get("/api/tickets").param("page", "1").param("size", "1").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.totalPages").value(Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("筛选：按状态 + 关键词过滤")
    void filterByStatusAndKeyword() throws Exception {
        createTicket(EMPLOYEE);

        mockMvc.perform(get("/api/tickets")
                        .param("status", "open")
                        .param("keyword", "VPN")
                        .with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/tickets").param("status", "NOT_A_STATUS").with(user(EMPLOYEE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("管理员指派：指派给 SUPPORT → IN_PROGRESS；重复指派 → 409")
    void adminAssign() throws Exception {
        long id = createTicket(EMPLOYEE);
        User supportUser = createUser(UserRole.SUPPORT);

        mockMvc.perform(post("/api/tickets/{id}/assign", id).with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + supportUser.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.assigneeId").value(supportUser.getId().intValue()));

        mockMvc.perform(post("/api/tickets/{id}/assign", id).with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + supportUser.getId() + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("指派校验：不能指派给 EMPLOYEE，SUPPORT 无权指派")
    void assignValidation() throws Exception {
        long id = createTicket(EMPLOYEE);
        User employee = createUser(UserRole.EMPLOYEE);

        mockMvc.perform(post("/api/tickets/{id}/assign", id).with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + employee.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        mockMvc.perform(post("/api/tickets/{id}/assign", id).with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":1}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------

    private long createTicket(LoginUser creator) throws Exception {
        String body = mockMvc.perform(post("/api/tickets").with(user(creator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private void claimAndResolve(long id) throws Exception {
        mockMvc.perform(post("/api/tickets/{id}/claim", id).with(user(SUPPORT)))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/tickets/{id}/status", id).with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RESOLVE"}
                                """))
                .andExpect(status().isOk());
    }

    private User createUser(UserRole role) {
        User user = new User();
        user.setUsername(role.name().toLowerCase() + "_" + System.nanoTime());
        user.setPasswordHash("{noop}password123");
        user.setFullName("测试" + role.name());
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }
}
