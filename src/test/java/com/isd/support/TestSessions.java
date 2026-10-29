package com.isd.support;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话统一存到 Redis（Spring Session）之后，MockMvc 里拿不到 {@code MockHttpSession} 了：
 * {@code request.getSession(false)} 返回的是 Spring Session 的 HttpSession 包装类，
 * 而 {@code MockHttpServletRequestBuilder.session(...)} 只接受 MockHttpSession，强转会 ClassCastException。
 *
 * 所以测试统一改成"从响应里取出 SESSION Cookie，后续请求带上 Cookie"——
 * 这也更接近真实浏览器行为，顺带覆盖了 Cookie 下发这一步。
 */
public final class TestSessions {

    /** Spring Session 的默认 Cookie 名（由 DefaultCookieSerializer 决定，不是 JSESSIONID） */
    public static final String COOKIE_NAME = "SESSION";

    private TestSessions() {
    }

    /** 登录并返回会话 Cookie（失败会直接断言失败，避免调用方拿到 null 继续跑） */
    public static Cookie login(MockMvc mockMvc, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return cookieOf(result);
    }

    /** 从任意响应里取会话 Cookie（登录、改密后 session id 会轮换，都可以用它拿新值） */
    public static Cookie cookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(COOKIE_NAME);
        assertThat(cookie).as("响应应下发 %s Cookie", COOKIE_NAME).isNotNull();
        return cookie;
    }

    public static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
