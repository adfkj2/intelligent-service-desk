package com.isd.kb;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库接口测试：CRUD、分类权限、条目归属权限、检索与通配符转义、OpenAPI 文档可用性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KbApiTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(8001L);
    private static final LoginUser SUPPORT = TestUsers.support(8002L);
    private static final LoginUser OTHER_SUPPORT = TestUsers.support(8003L);
    private static final LoginUser ADMIN = TestUsers.admin(8004L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("分类：ADMIN 可建、重名 409、EMPLOYEE 403、登录即可查列表")
    void categoryPermissions() throws Exception {
        mockMvc.perform(post("/api/kb/categories").with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"网络","description":"网络类问题"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("网络"));

        mockMvc.perform(post("/api/kb/categories").with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"网络"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(post("/api/kb/categories").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"账号"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/kb/categories").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("条目：SUPPORT 可建，EMPLOYEE 403，分类不存在 404，详情带分类名")
    void createItem() throws Exception {
        long categoryId = createCategory("硬件");

        mockMvc.perform(post("/api/kb/items").with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId
                                + ",\"title\":\"打印机无法连接\",\"content\":\"重启打印服务即可\",\"tags\":\"打印机,驱动\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryName").value("硬件"))
                .andExpect(jsonPath("$.data.createdBy").value(8002))
                .andExpect(jsonPath("$.data.tags").value("打印机,驱动"));

        mockMvc.perform(post("/api/kb/items").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"title\":\"x\",\"content\":\"y\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/kb/items").with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":999999,\"title\":\"x\",\"content\":\"y\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("列表：可按分类过滤，摘要不返回正文")
    void listAndFilter() throws Exception {
        long network = createCategory("网络二");
        long account = createCategory("账号二");
        createItem(SUPPORT, network, "VPN 无法连接", "检查客户端版本", "vpn");
        createItem(SUPPORT, network, "WiFi 掉线", "更换信道", "wifi");
        createItem(SUPPORT, account, "密码重置", "联系管理员", "账号");

        mockMvc.perform(get("/api/kb/items").param("size", "100").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(3)));

        mockMvc.perform(get("/api/kb/items")
                        .param("categoryId", String.valueOf(network))
                        .param("size", "100")
                        .with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].categoryId",
                        Matchers.everyItem(Matchers.is((int) network))))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist());
    }

    @Test
    @DisplayName("检索：标题/正文/标签都能命中，空关键词 400，通配符被转义")
    void search() throws Exception {
        long categoryId = createCategory("检索");
        createItem(SUPPORT, categoryId, "打印机无法连接", "重启打印服务即可恢复", "打印机,驱动");

        mockMvc.perform(get("/api/kb/search").param("q", "打印机").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].title").value(Matchers.containsString("打印机")));

        // 命中标签
        mockMvc.perform(get("/api/kb/search").param("q", "驱动").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(1)));

        // 命中正文
        mockMvc.perform(get("/api/kb/search").param("q", "重启打印服务").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(Matchers.greaterThanOrEqualTo(1)));

        // 空关键词：400 而不是"返回全库"
        mockMvc.perform(get("/api/kb/search").param("q", "  ").with(user(EMPLOYEE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 通配符注入：搜 "%" 必须当成字面量，不应该把全表捞出来
        mockMvc.perform(get("/api/kb/search").param("q", "%").with(user(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 缺少必填参数：400 而不是被兜底成 500
        mockMvc.perform(get("/api/kb/search").with(user(EMPLOYEE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("修改：非创建者 403，创建者与 ADMIN 可以改，分类不存在 404")
    void updateItem() throws Exception {
        long categoryId = createCategory("修改");
        long otherCategoryId = createCategory("修改二");
        long itemId = createItem(SUPPORT, categoryId, "旧标题", "旧正文", null);

        mockMvc.perform(put("/api/kb/items/{id}", itemId).with(user(OTHER_SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"title\":\"新标题\",\"content\":\"新正文\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(put("/api/kb/items/{id}", itemId).with(user(SUPPORT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + otherCategoryId + ",\"title\":\"新标题\",\"content\":\"新正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新标题"))
                .andExpect(jsonPath("$.data.categoryName").value("修改二"));

        mockMvc.perform(put("/api/kb/items/{id}", itemId).with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":999999,\"title\":\"x\",\"content\":\"y\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("删除：非创建者 403，创建者删除后详情 404")
    void deleteItem() throws Exception {
        long categoryId = createCategory("删除");
        long itemId = createItem(SUPPORT, categoryId, "待删除", "正文", null);

        mockMvc.perform(delete("/api/kb/items/{id}", itemId).with(user(OTHER_SUPPORT)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/kb/items/{id}", itemId).with(user(SUPPORT)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/kb/items/{id}", itemId).with(user(EMPLOYEE)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("OpenAPI 文档可匿名访问，且包含知识库接口")
    void openApiDocsAvailable() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(docs).contains("/api/kb/items").contains("/api/tickets").contains("/api/auth/login");
    }

    // ------------------------------------------------------------------

    private long createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/api/kb/categories").with(user(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private long createItem(LoginUser author, long categoryId, String title, String content, String tags) throws Exception {
        String tagsJson = tags == null ? "" : ",\"tags\":\"" + tags + "\"";
        String body = mockMvc.perform(post("/api/kb/items").with(user(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"title\":\"" + title
                                + "\",\"content\":\"" + content + "\"" + tagsJson + "}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }
}
