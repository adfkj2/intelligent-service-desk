package com.isd.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;

/**
 * 需要"取当前值"的指标放这里：Gauge 只表示瞬时量，累计量一律用 Counter。
 */
@Configuration
public class MetricsConfig {

    @Bean
    Gauge activeSessions(MeterRegistry registry, SessionRegistry sessionRegistry) {
        return Gauge.builder("isd.sessions.active", sessionRegistry, this::countSessions)
                .description("当前有效会话总数")
                .register(registry);
    }

    private double countSessions(SessionRegistry sessionRegistry) {
        return sessionRegistry.getAllPrincipals().stream()
                .mapToInt(principal -> sessionRegistry.getAllSessions(principal, false).size())
                .sum();
    }
}
