package com.isd.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 调整用户角色的请求体。
 * 只校验非空，具体合法值交给 UserRole.of 统一判断（与 StateMachine 的写法保持一致：
 * 枚举自己的合法性只在一个地方定义，避免 DTO 正则与枚举悄悄不同步）。
 */
public record UpdateUserRoleRequest(

        @NotBlank(message = "role 不能为空")
        String role
) {
}
