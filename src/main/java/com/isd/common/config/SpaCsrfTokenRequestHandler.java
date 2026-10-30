package com.isd.common.config;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * SPA（前后端同源 + Cookie 双提交）场景下的 CSRF 处理器。
 *
 * <p>为什么需要它：Spring Security 6 默认用 {@link XorCsrfTokenRequestAttributeHandler}，
 * 它输出到页面的 token 是 XOR 编码过的；而 SPA 是把 <b>Cookie 里的原始 token</b> 原样放进
 * {@code X-XSRF-TOKEN} 头再发回来。用同一个 handler 处理两边就会永远对不上（403）。
 *
 * <p>官方方案是分工：
 * <ul>
 *   <li>{@code handle}：写回页面/属性时用 XOR，保留 BREACH 防护；</li>
 *   <li>{@code resolveCsrfTokenValue}：校验时用 plain，直接读请求头里的原始值。</li>
 * </ul>
 * 代价是丢掉了"请求头里 token 的 BREACH 编码"这一层——但 SPA 的 token 本来就在响应
 * Cookie 里明文可见，这层编码对它没有额外收益。
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.xor.handle(request, response, csrfToken);
        /*
         * 主动取一次 token：CsrfFilter 拿到的是延迟加载的 token，
         * 不触发的话首个 GET 不会写 XSRF-TOKEN Cookie，紧接着的登录 POST 必然 403。
         */
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return this.plain.resolveCsrfTokenValue(request, csrfToken);
    }
}
