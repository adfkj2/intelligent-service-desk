package com.isd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码（首次登录强制改密也走这个接口）。
 * 原密码错误返回 400 而不是 401：401 会被前端拦截器当成会话失效并踢回登录页，
 * 这里用户只是输错了一个字段，应该留在表单里改。
 */
public record ChangePasswordRequest(

        @NotBlank(message = "请输入原密码")
        String oldPassword,

        @NotBlank(message = "请输入新密码")
        @Size(min = 8, max = 64, message = "新密码长度需在 8-64 之间")
        String newPassword
) {
}
