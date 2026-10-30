package com.isd.common.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Spring Security 配置（Session 方案）。
 *
 * 关键取舍：
 * 1. 开启 CSRF（Cookie 双提交）：Session Cookie 虽已设 SameSite=Lax，但 Lax 只挡住跨站表单的
 *    顶层导航，挡不住同站子域与老浏览器；用 CookieCsrfTokenRepository 让前端在请求头回传 token，
 *    才算真正验证"请求来自我们的页面"。细节见 {@link SpaCsrfTokenRequestHandler}。
 * 2. 关闭 formLogin / httpBasic：只保留我们自己的 JSON 登录接口，避免暴露默认登录页与 Basic 认证入口。
 * 3. 401/403 也必须返回统一 JSON 结构，否则前端的 axios 拦截器拿到的是一段 HTML。
 * 4. CSP 按属性开关装配（{@code isd.security.csp.enabled}），只在 prod profile 打开：
 *    开发期 Vite HMR 需要 eval/WebSocket，Swagger UI 依赖内联脚本，一律开启会让本地调试不可用。
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
                                                   IsdMetrics metrics,
                                                   @Value("${isd.security.csp.enabled:false}") boolean cspEnabled)
            throws Exception {
        http
                /*
                 * CSRF 用 Cookie 双提交：token 放在可被 JS 读到的 XSRF-TOKEN Cookie 里，
                 * axios 同源请求会自动带上 X-XSRF-TOKEN 头，服务端比对两者。
                 * 只读请求（GET/HEAD/OPTIONS）不校验，所以列表页、静态资源都不受影响。
                 */
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                /*
                 * CSP 只决定"加载/执行什么"，与业务无关，所以放在 headers 里统一输出。
                 * 生产开启后由 Nginx 原样透传给浏览器；策略集中在 CspPolicy，测试守护开/关两种形态。
                 */
                .headers(headers -> {
                    if (cspEnabled) {
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(CspPolicy.PROD));
                    }
                })
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // 首个 GET 就要把 XSRF-TOKEN 下发，否则登录页的第一次 POST 无从带 token
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
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
