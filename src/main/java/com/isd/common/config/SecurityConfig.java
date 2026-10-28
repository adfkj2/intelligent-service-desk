package com.isd.common.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Spring Security 配置（Session 方案）。
 *
 * 三个关键取舍：
 * 1. 关闭 CSRF：本项目的客户端是同源前端 + JSON 接口，Session Cookie 已设 SameSite=Lax，
 *    跨站表单提交不会带上 Cookie；若要更严格，可换成 CookieCsrfTokenRepository 双提交模式。
 * 2. 关闭 formLogin / httpBasic：只保留我们自己的 JSON 登录接口，避免暴露默认登录页与 Basic 认证入口。
 * 3. 401/403 也必须返回统一 JSON 结构，否则前端的 axios 拦截器拿到的是一段 HTML。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 手工登录时需要它来保存 SecurityContext，见 AuthController#login */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 会话注册表：用于“重置密码后踢人”。
     * 手工登录必须在 AuthController 里显式 registerNewSession，否则这里查不到会话。
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** 会话创建/销毁事件同步到 SessionRegistry；不注册的话 registry 会残留死会话 */
    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   ObjectMapper objectMapper,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        /*
                         * -1 表示不限并发数，但要开启并发控制才会装配 ConcurrentSessionFilter 来检查 expireNow 标记；
                         * 过期后返回 401 JSON，而不是 Spring 默认的 "/?expired" 302 跳转。
                         */
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> writeJson(event.getResponse(),
                                ErrorCode.UNAUTHORIZED, objectMapper, "会话已失效，请重新登录")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 登录页“忘记密码”需要未登录也能读取管理员联系方式
                        .requestMatchers(HttpMethod.GET, "/api/auth/admin-contacts").permitAll()
                        .requestMatchers("/api/ping", "/error").permitAll()
                        // 接口文档放行：生产环境应改为仅内网/IP 白名单可见
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        /*
                         * 前端静态资源与 SPA 页面路由必须放行，否则未登录用户连登录页都拿不到（会先被 401 拦掉）。
                         * 只列具体路径，不用 /** 通配：/api/** 的鉴权规则与既有的 404 语义都不能被这部分吞掉。
                         */
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**",
                                "/login", "/change-password", "/tickets", "/tickets/**", "/kb",
                                "/admin/users").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, ErrorCode.UNAUTHORIZED, objectMapper,
                                        "未登录或会话已失效，请先登录"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, ErrorCode.FORBIDDEN, objectMapper,
                                        "无权访问该资源")));
        return http.build();
    }

    private void writeJson(HttpServletResponse response, ErrorCode code, ObjectMapper objectMapper,
                           String message) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code.code(), message));
    }
}
