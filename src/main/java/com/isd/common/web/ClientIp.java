package com.isd.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 来源 IP 的唯一取值口径（登录限流、AI 限流共用）。
 *
 * 只用 {@code getRemoteAddr()}，不读 {@code X-Forwarded-For}：这个头客户端可以随便伪造，
 * 在没有可信反向代理的前提下读它，等于把按 IP 限流直接关掉（攻击者每次请求换一个假 IP 即可）。
 * 真上了网关，再统一改成 ForwardedHeaderFilter 或网关注入的可信头。
 *
 * 抽成公共工具而不是每个 Controller 各写一份：两处实现一旦漂移（比如某一处开始读 XFF），
 * 就会出现"登录限流按真实 IP、AI 限流按伪造 IP"这种很难排查的差异。
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }
}
