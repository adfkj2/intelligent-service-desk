package com.isd.auth.controller;

import java.util.List;

import com.isd.auth.dto.ResetPasswordResponse;
import com.isd.auth.dto.UpdateUserRoleRequest;
import com.isd.auth.dto.UpdateUserStatusRequest;
import com.isd.auth.dto.UserResponse;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.auth.service.UserService;
import com.isd.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理：URL 级规则 `/api/admin/**` 已在 SecurityConfig 中限定 ADMIN，这里不再重复判断角色。
 * 双层校验的取舍：URL 层挡住"不该进来的人"，Service 层管"能进来也不代表能这么干"（如禁用自己）。
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "用户管理", description = "ADMIN 专用：用户列表与启用/禁用")
public class AdminUserController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    public AdminUserController(UserService userService, CurrentUserProvider currentUserProvider) {
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<List<UserResponse>> list() {
        return ApiResponse.ok(userService.listUsers());
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<UserResponse> updateStatus(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateUserStatusRequest request) {
        return ApiResponse.ok(userService.updateStatus(id, request.status(), currentUserProvider.requireId()));
    }

    /**
     * 调整角色：注册接口固定给 EMPLOYEE，提权只能由管理员从这里完成。
     * 返回更新后的用户，前端直接用它刷新表格行，不必整页重新拉取。
     */
    @PatchMapping("/{id}/role")
    public ApiResponse<UserResponse> updateRole(@PathVariable Long id,
                                                @Valid @RequestBody UpdateUserRoleRequest request) {
        return ApiResponse.ok(userService.updateRole(id, request.role(), currentUserProvider.requireId()));
    }

    /**
     * 重置密码：生成一次性临时密码，对方现有会话立即失效，首次登录必须改密。
     * 响应加 Cache-Control: no-store，避免临时密码被浏览器或中间代理缓存。
     */
    @PostMapping("/{id}/password/reset")
    public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPassword(@PathVariable Long id) {
        ResetPasswordResponse result = userService.resetPassword(id, currentUserProvider.requireId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(result));
    }
}
