package com.isd.auth.dto;

/**
 * 管理员重置密码的结果。
 * temporaryPassword 是明文，只在这一次响应里出现；前端展示一次后即丢弃，后端不落库、不写日志。
 */
public record ResetPasswordResponse(Long userId, String username, String temporaryPassword,
                                    boolean mustChangePassword) {
}
