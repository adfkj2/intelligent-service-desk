package com.isd.common.config;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把调用线程的 SecurityContext 与 MDC 一起带到线程池里。
 *
 * <p>原来用 {@code DelegatingSecurityContextExecutorService} 只搬了身份，AI 调用那几行日志
 * 会丢 traceId；换成 ThreadPoolTaskExecutor 后必须自己补齐，而且要成对 restore/clear，
 * 否则线程复用会把上一个请求的身份或 traceId 泄漏给下一个任务。
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        SecurityContext context = SecurityContextHolder.getContext();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return () -> {
            SecurityContext previousContext = SecurityContextHolder.getContext();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                SecurityContextHolder.setContext(context);
                if (mdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(mdc);
                }
                runnable.run();
            } finally {
                SecurityContextHolder.setContext(previousContext);
                if (previousMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousMdc);
                }
            }
        };
    }
}
