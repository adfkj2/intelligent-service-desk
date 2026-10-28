package com.isd.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 * 密码只做长度校验，不做复杂度规则：复杂度规则（大小写数字符号）在拦截机器撞库上收益很低，
 * 长度才是主要因素；真正要防撞库靠的是限流 + 失败锁定。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 50, message = "用户名长度需在 4-50 之间")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在 8-64 之间")
        String password,

        @Size(max = 50, message = "显示名不能超过 50 个字符")
        String fullName
) {
}
