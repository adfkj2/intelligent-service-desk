package com.isd.auth.dto;

import com.isd.auth.entity.User;
import com.isd.auth.security.LoginUser;

/**
 * 用户对外视图：只暴露必要字段，绝不包含 passwordHash。
 *
 * phone/email 供个人资料与用户管理展示；mustChangePassword 供前端决定是否强制跳改密页。
 * 真实密码与临时密码永远不会出现在这里。
 */
public record UserResponse(Long id, String username, String fullName, String role, Integer status,
                           String phone, String email, boolean mustChangePassword) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole() == null ? null : user.getRole().name(), user.getStatus(),
                user.getPhone(), user.getEmail(),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    /**
     * 仅供“刚完成认证、还没回查数据库”的场景兜底使用。
     * 登录与 /auth/me 都应该从数据库重新读取，保证联系方式是最新值。
     */
    public static UserResponse from(LoginUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(),
                user.getRole().name(), user.isEnabled() ? 1 : 0,
                null, null, user.isMustChangePassword());
    }
}
