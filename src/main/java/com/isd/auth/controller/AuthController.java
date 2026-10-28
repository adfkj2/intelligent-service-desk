package com.isd.auth.controller;

import java.util.List;

import com.isd.auth.dto.AdminContactResponse;
import com.isd.auth.dto.ChangePasswordRequest;
import com.isd.auth.dto.LoginRequest;
import com.isd.auth.dto.RegisterRequest;
import com.isd.auth.dto.UpdateContactRequest;
import com.isd.auth.dto.UserResponse;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.auth.security.LoginUser;
import com.isd.auth.service.UserService;
import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.ApiResponse;
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
import org.springframework.security.core.session.SessionRegistry;
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
    private final SessionRegistry sessionRegistry;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          UserService userService,
                          CurrentUserProvider currentUserProvider,
                          SessionRegistry sessionRegistry) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.sessionRegistry = sessionRegistry;
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
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (DisabledException ex) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用，请联系管理员");
        } catch (AuthenticationException ex) {
            // 不区分「用户不存在」和「密码错误」，避免账号枚举
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        // 会话固定攻击防护：登录成功后更换 session id（等价于 ChangeSessionIdAuthenticationStrategy）
        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }

        /*
         * 手工登录不经过 SessionManagementFilter，必须自己把会话登记到 SessionRegistry。
         * 否则“重置密码后踢人”找不到目标会话，SessionRevoker 会是空转。
         */
        sessionRegistry.registerNewSession(httpRequest.getSession().getId(), authentication.getPrincipal());

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
     * 顺带轮换 session id 并同步 SessionRegistry，避免旧会话 id 残留。
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

        httpRequest.changeSessionId();
        String newSessionId = httpRequest.getSession().getId();
        sessionRegistry.removeSessionInformation(oldSessionId);
        sessionRegistry.registerNewSession(newSessionId, refreshed);

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
