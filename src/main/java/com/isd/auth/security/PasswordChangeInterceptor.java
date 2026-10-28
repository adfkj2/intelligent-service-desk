package com.isd.auth.security;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isd.common.exception.ErrorCode;
import com.isd.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 强制改密的第二道防线（第一道是前端路由守卫）。
 *
 * 为什么后端也要拦：前端跳转只是体验，用户完全可以直接调 /api/tickets。
 * 只要 Session 里仍是 mustChangePassword=true，除改密、查看自己、退出、公开联系方式外的
 * /api/** 一律 403。
 */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/auth/change-password",
            "/api/auth/me",
            "/api/auth/logout",
            "/api/auth/admin-contacts"
    );

    private final ObjectMapper objectMapper;

    public PasswordChangeInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || !loginUser.isMustChangePassword()) {
            return true;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (ALLOWED_PATHS.contains(path)) {
            return true;
        }

        response.setStatus(ErrorCode.FORBIDDEN.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(ErrorCode.FORBIDDEN.code(), "请先修改初始密码"));
        return false;
    }
}
