package com.isd.ticket;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单基础接口集成测试。
 * @SpringBootTest：启动完整容器（真实 MyBatis + MySQL + Security）；
 * @Transactional：每个测试方法结束后自动回滚，不会污染开发库；
 * MockMvc：不启动真实 Tomcat，直接走 DispatcherServlet 链路；
 * .with(user(...))：绕过真实登录直接注入身份，用于测业务逻辑而非认证流程本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketApiTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(1001L);

    private static final String VALID_BODY = """
            {"title":"打印机无法连接","description":"3楼打印机离线","category":"IT_FAULT","priority":"HIGH"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未登录访问工单接口：401，统一结构")
    void unauthenticated() throws Exception {
        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("创建工单：201，返回 VO（不含 passwordHash/version），数据库生成字段已回填")
    void createTicket() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.ticketNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.creatorId").value(1001))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }

    @Test
    @DisplayName("标题为空：400，提示包含字段名")
    void createWithBlankTitle() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"","category":"IT_FAULT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("标题")));
    }

    @Test
    @DisplayName("分类非法：400")
    void createWithIllegalCategory() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"测试工单","category":"NOT_A_CATEGORY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("查询不存在的工单：404，业务码 40400")
    void getMissingTicket() throws Exception {
        mockMvc.perform(get("/api/tickets/{id}", 999999).with(user(EMPLOYEE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("工单不存在")));
    }

    @Test
    @DisplayName("创建后按 ID 查回，字段一致")
    void createThenGet() throws Exception {
        String body = mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        long id = new ObjectMapper().readTree(body).path("data").path("id").asLong();

        mockMvc.perform(get("/api/tickets/{id}", id).with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("打印机无法连接"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.assigneeId").doesNotExist());
    }

    @Test
    @DisplayName("分页列表：默认分页参数生效，返回统一分页结构")
    void listTickets() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        mockMvc.perform(get("/api/tickets").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1001));
    }

    @Test
    @DisplayName("路径参数不是数字：400")
    void getWithNonNumericId() throws Exception {
        mockMvc.perform(get("/api/tickets/abc").with(user(EMPLOYEE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("按状态统计：小写入参被规范化，返回计数")
    void statsWithValidStatus() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tickets/stats/{status}", "open").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.count").value(Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("按状态统计：非法状态返回 400")
    void statsWithIllegalStatus() throws Exception {
        mockMvc.perform(get("/api/tickets/stats/{status}", "NOT_A_STATUS").with(user(EMPLOYEE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("不支持的方法：405 而不是 500")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/tickets/{id}", 1L).with(user(EMPLOYEE)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("错误的 Content-Type：415 而不是 500")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/tickets").with(user(EMPLOYEE))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
