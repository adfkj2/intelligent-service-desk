package com.isd.common.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 保证每个请求都会把 CSRF token 落到响应 Cookie 上。
 *
 * <p>没有它的时候，{@code CookieCsrfTokenRepository} 只在"需要写 token"的时机下发 Cookie；
 * 用户打开首页（GET）拿不到 XSRF-TOKEN，登录页第一个 POST 就被 403 拦掉——而前端根本无从补救，
 * 因为它没有 token 可带。
 *
 * <p>显式调用 {@code getToken()} 是为了让延迟加载的 token 真正生成：
 * 只是把属性塞进 request 而不取值，生成逻辑不会触发。
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
