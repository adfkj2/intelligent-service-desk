package com.isd.common.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

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

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   ObjectMapper objectMapper,
                                                   IsdMetrics metrics) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                /*
                 * 会话存在 Redis（Spring Session），"踢人"= 删除会话记录，而不是给 SessionInformation 打过期标记。
                 * 因此这里不再装配 ConcurrentSessionFilter / SessionRegistry：
                 * 被删掉的会话下次请求就是未登录，由下面的 authenticationEntryPoint 统一返回 401 JSON。
                 */
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // 登录页“忘记密码”需要未登录也能读取管理员联系方式
                        .requestMatchers(HttpMethod.GET, "/api/auth/admin-contacts").permitAll()
                        .requestMatchers("/api/ping", "/error").permitAll()
                        /*
                         * Actuator 端点只映射在管理端口 8082（且绑 127.0.0.1）：Spring Security
                         * 的过滤器链同样作用于该端口，不放行的话 Prometheus 抓取会拿到 401。
                         * 8081 上没有这些端点，放行后访问落到 404，不会泄漏任何信息。
                         */
                        .requestMatchers("/actuator/**").permitAll()
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
                        .accessDeniedHandler((request, response, ex) -> {
                            // URL 规则拦下的越权（如员工访问 /api/admin/**），是最常见的探测信号
                            metrics.recordForbidden("url");
                            writeJson(response, ErrorCode.FORBIDDEN, objectMapper, "无权访问该资源");
                        }));
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
