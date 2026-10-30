package com.isd.support;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF 双提交的真实行为。
 *
 * <p>其余用例通过 {@link CsrfTestSupport} 默认带上合法 token（等价于浏览器自动带 X-XSRF-TOKEN），
 * 这个类反过来用<b>没有定制</b>的 MockMvc，专门验证"不带 token / token 不匹配"必须被拒——
 * 否则"全绿"只证明校验被关掉了，而不是被满足了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CsrfProtectionTest extends AbstractIntegrationTest {

    private static final String REGISTER_BODY =
            "{\"username\":\"%s\",\"password\":\"csrf12345\",\"fullName\":\"CSRF 用例\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    /** 不带任何默认 CSRF token 的 MockMvc：模拟"前端忘了带 token"的请求 */
    private MockMvc rawMvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private static Cookie csrfCookie(String value) {
        return new Cookie(CsrfTestSupport.COOKIE_NAME, value);
    }

    @Test
    void 首个GET就下发XSRF_TOKEN_Cookie() throws Exception {
        MvcResult result = rawMvc().perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getCookie(CsrfTestSupport.COOKIE_NAME))
                .as("首页/探活请求必须把 CSRF token 写进 Cookie，否则登录页第一次 POST 无从带 token")
                .isNotNull();
    }

    @Test
    void 写请求缺少token返回403() throws Exception {
        rawMvc().perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY.formatted("csrf_missing")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void Cookie与请求头不一致返回403() throws Exception {
        rawMvc().perform(post("/api/auth/register")
                        .cookie(csrfCookie("token-in-cookie"))
                        .header(CsrfTestSupport.HEADER_NAME, "token-in-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY.formatted("csrf_mismatch")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void 只读请求不需要token() throws Exception {
        rawMvc().perform(get("/api/ping")).andExpect(status().isOk());
    }

    /**
     * 带合法 token 的写请求能走通完整业务链路：注册 → 登录 → 建单。
     *
     * <p>这里用默认 MockMvc（token 由 {@link CsrfTestSupport} 注入成 Cookie + 请求头对），
     * 等价于浏览器行为；"少带 token"与"两边不一致"由上面两个用例负责证伪。
     */
    @Test
    void 带匹配token的注册与建单可以正常完成() throws Exception {
        String username = "csrf_ok_" + System.nanoTime();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY.formatted(username)))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSessions.loginBody(username, "csrf12345")))
                .andExpect(status().isOk())
                .andReturn();

        Cookie session = TestSessions.cookieOf(login);
        mockMvc.perform(post("/api/tickets")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"CSRF 建单\",\"description\":\"带 token 应成功\","
                                + "\"category\":\"IT_FAULT\",\"priority\":\"MEDIUM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    /** 默认 MockMvc（带 CsrfTestSupport 注入的 token）也应保持可用，避免定制把正常路径改坏 */
    @Test
    void 默认MockMvc带token的写请求可用() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY.formatted("csrf_default_" + System.nanoTime())))
                .andExpect(status().isCreated());
    }
}
