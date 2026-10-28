package com.isd.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给每个请求分配一个 traceId，写进 MDC 并回写到响应头。
 *
 * <p>日志 pattern 里用 {@code %X{traceId}} 输出，一次请求从 Controller 到 Service 到 Mapper
 * 的日志就能按 id 串起来；前端报错时把响应头里的 id 报过来即可定位。
 *
 * <p>只接受 1-32 位的字母数字与短横线：直接信任客户端 header 会被注入换行符伪造日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    /** 只接受 1-32 位字母数字与短横线；超长或含非法字符一律重新生成，避免往日志里灌垃圾 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolve(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会复用，必须清理，否则下一个请求可能带着上一个的 id
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
