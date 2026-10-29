package com.isd.auth.controller;

import java.util.List;

import com.isd.auth.dto.AdminContactResponse;
import com.isd.auth.dto.ChangePasswordRequest;
import com.isd.auth.dto.LoginRequest;
import com.isd.auth.dto.RegisterRequest;
import com.isd.auth.dto.UpdateContactRequest;
import com.isd.auth.dto.UserResponse;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.auth.security.LoginAttemptGuard;
import com.isd.auth.security.LoginUser;
import com.isd.auth.service.UserService;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import com.isd.common.result.ApiResponse;
import com.isd.common.web.ClientIp;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（Session 方案）。
 *
 * 为什么用 Session 而不是 JWT：模块化单体、无多端诉求，Session 注销即失效、可服务端踢人，
 * 不需要自己实现刷新令牌与吊销列表。JWT 的取舍可以讲，但 v1 不引入这份复杂度。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "注册 / 登录 / 注销 / 当前用户")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final LoginAttemptGuard loginAttemptGuard;
    private final IsdMetrics metrics;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          UserService userService,
                          CurrentUserProvider currentUserProvider,
                          LoginAttemptGuard loginAttemptGuard,
                          IsdMetrics metrics) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.loginAttemptGuard = loginAttemptGuard;
        this.metrics = metrics;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse created = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * 登录：手工调用 AuthenticationManager，而不是用 formLogin。
     * 手工登录必须自己把 SecurityContext 存进 Session（默认 requireExplicitSave），否则下一个请求还是匿名的。
     */
    @PostMapping("/login")
    public ApiResponse<UserResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) {
        String clientIp = ClientIp.of(httpRequest);
        long lockedSeconds = loginAttemptGuard.lockedSeconds(request.username(), clientIp);
        if (lockedSeconds > 0) {
            // 先判锁再验证凭据：锁定期间连"密码对不对"都不告诉对方，避免变成密码探测接口
            metrics.recordLogin("locked");
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "登录失败次数过多，请 " + Math.max(1, (lockedSeconds + 59) / 60) + " 分钟后再试");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (DisabledException ex) {
            // 密码是对的、只是账号被禁用：不计入失败次数，否则管理员禁用后对方会把自己锁住
            metrics.recordLogin("disabled");
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        } catch (AuthenticationException ex) {
            loginAttemptGuard.recordFailure(request.username(), clientIp);
            // 指标里区分失败类型（内部可见），但响应保持统一文案，不对调用方泄漏账号是否存在
            metrics.recordLogin("bad_credentials");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        loginAttemptGuard.recordSuccess(request.username());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // 会话固定攻击防护：登录成功后更换 session id（等价于 ChangeSessionIdAuthenticationStrategy）
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        /*
         * 会话存在 Redis（Spring Session）：saveContext 写入 SPRING_SECURITY_CONTEXT 属性时，
         * Spring Session 会顺带维护 principal 索引，"重置密码后踢人"正是按这个索引查会话。
         * 不再需要手工登记 SessionRegistry —— 那是进程内实现才需要的东西。
         */
        metrics.recordLogin("success");

        // 从数据库回读，保证 phone/email/mustChangePassword 都是最新值（Session 里的 principal 可能是旧的）
        LoginUser principal = (LoginUser) authentication.getPrincipal();
        return ApiResponse.ok(userService.getById(principal.getId()));
    }

    /**
     * 登录页“忘记密码”用：公开接口，只返回启用中且填写了联系方式的管理员。
     * 每次打开弹窗实时请求，管理员降权/禁用/改联系方式后立即生效。
     */
    @GetMapping("/admin-contacts")
    public ApiResponse<List<AdminContactResponse>> adminContacts() {
        return ApiResponse.ok(userService.listAdminContacts());
    }

    /** 维护自己的联系方式（管理员用于“忘记密码”展示） */
    @PutMapping("/me/contact")
    public ApiResponse<UserResponse> updateContact(@Valid @RequestBody UpdateContactRequest request) {
        return ApiResponse.ok(userService.updateContact(currentUserProvider.requireId(), request));
    }

    /**
     * 修改自己的密码，同时承担“首次登录强制改密”。
     *
     * 改密成功后必须重建 Session 里的 LoginUser：它记录着 mustChangePassword，
     * 只更新数据库而不更新 principal，拦截器会继续把后续请求全部挡成 403。
     * 顺带轮换 session id：Spring Session 在提交时迁移会话，旧 id 的键不会残留。
     */
    @PostMapping("/change-password")
    public ApiResponse<UserResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        LoginUser current = currentUserProvider.require();
        String oldSessionId = httpRequest.getSession(true).getId();

        UserResponse updated = userService.changePassword(current.getId(), request, oldSessionId);

        LoginUser refreshed = new LoginUser(current.getId(), current.getUsername(), current.getPassword(),
                current.getFullName(), current.getRole(), current.isEnabled(), false);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                refreshed, refreshed.getPassword(), refreshed.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // 改密后轮换 session id：旧 id 在 Redis 里被迁走，SessionRevoker 已经按旧 id 跳过本会话
        httpRequest.changeSessionId();

        return ApiResponse.ok(updated);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            // 清 SecurityContext + 失效 Session，双管齐下
            new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        }
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        // 从数据库取：Session 里的 LoginUser 不包含电话/邮箱，也无法感知个人资料的最新修改
        return ApiResponse.ok(userService.getById(currentUserProvider.requireId()));
    }
}
