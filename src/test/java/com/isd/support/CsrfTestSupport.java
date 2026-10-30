package com.isd.support;

import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 给所有 MockMvc 请求默认带上合法的 CSRF token（Cookie + 请求头成对）。
 *
 * <p>为什么不在 100 个 {@code mockMvc.perform(post(...))} 上逐个加 {@code .with(csrf())}：
 * 那是纯机械改动，会让每个用例都多一行噪声，且以后新写用例很容易漏；
 * 而漏掉的表现是 403，排查成本高。这里用 {@code MockMvcBuilderCustomizer} 统一注入，
 * 等价于「浏览器每个请求都自动带上了 XSRF-TOKEN」这个真实前提。
 *
 * <p>注意这并没有绕过校验：Cookie 与请求头的值必须一致，且由 Spring Security 的
 * CsrfFilter 真正比对。缺 token、token 不匹配这两种失败路径由
 * {@link CsrfProtectionTest} 用未经过本类定制的 MockMvc 单独验证。
 */
@Component
public class CsrfTestSupport implements MockMvcBuilderCustomizer {

    /** 固定的合法 token：Cookie 仓库本身不存状态，值只要两边一致即可 */
    public static final String TOKEN = "5d9c1f0e-csrf-test-token";

    /** 与 CookieCsrfTokenRepository 的默认命名保持一致；常量在 Spring Security 6 里不是 public，这里显式写死 */
    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.defaultRequest(get("/")
                .cookie(new Cookie(COOKIE_NAME, TOKEN))
                .header(HEADER_NAME, TOKEN));
    }
}
