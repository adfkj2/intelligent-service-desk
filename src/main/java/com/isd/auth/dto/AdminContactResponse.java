package com.isd.auth.dto;

import com.isd.auth.entity.User;

/**
 * 登录页“忘记密码”公开视图。
 * 刻意不包含 username、角色、id：未登录的人只需要知道找谁、怎么联系，
 * 少暴露一个字段就少一分被枚举的价值。
 */
public record AdminContactResponse(String fullName, String phone, String email) {

    public static AdminContactResponse from(User user) {
        return new AdminContactResponse(user.getFullName(), user.getPhone(), user.getEmail());
    }
}
