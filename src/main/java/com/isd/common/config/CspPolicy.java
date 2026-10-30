package com.isd.common.config;

/**
 * 生产环境的 CSP 策略常量。
 *
 * <p>为什么单独一个类：策略字符串一旦散落在 {@code SecurityConfig}、测试和文档里，
 * 改一处忘一处就会出现「测试通过、线上拦截」。集中在这里，改策略必须同时过
 * {@code SecurityHeadersTest}。
 *
 * <p>逐项取舍：
 * <ul>
 *   <li>{@code script-src 'self'}：前端产物是 Vite 打包后的外部 JS，不需要内联脚本，
 *       因此可以不放 {@code 'unsafe-inline'} / {@code 'unsafe-eval'}——这是 CSP 的主要价值所在；</li>
 *   <li>{@code style-src 'self' 'unsafe-inline'}：Element Plus 的弹层/下拉通过内联 style
 *       做定位（popper），去掉后弹窗会错位。允许内联样式不构成脚本执行风险；</li>
 *   <li>{@code img-src 'self' data: blob:}：data/blob 供前端预览与图标使用，不放外域；</li>
 *   <li>{@code object-src 'none'}、{@code frame-ancestors 'none'}、{@code base-uri 'self'}：
 *       堵住插件、点击劫持与 base 标签劫持；</li>
 *   <li>{@code connect-src 'self'}：XHR/fetch 只允许同源，AI 请求也走同源 {@code /api}。</li>
 * </ul>
 *
 * <p><b>只在 prod profile 启用</b>（{@code isd.security.csp.enabled=true}）：
 * 开发期 Vite HMR 需要 eval 与 WebSocket，Swagger UI 也依赖内联脚本，开启后本地调试会全量报违规。
 */
public final class CspPolicy {

    public static final String PROD = String.join("; ",
            "default-src 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'",
            "form-action 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'");

    private CspPolicy() {
    }
}
