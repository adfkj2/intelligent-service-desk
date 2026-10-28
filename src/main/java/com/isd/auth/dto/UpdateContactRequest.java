package com.isd.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 维护自己的联系方式。
 * 手机号与邮箱都可空（允许先清空再填），但服务层会要求管理员至少保留一项，
 * 否则登录页“忘记密码”就没有可用信息。
 */
public record UpdateContactRequest(

        @Pattern(regexp = "^$|^[0-9+\\-\\s]{5,20}$", message = "手机号格式不正确，只允许数字、+、-、空格，长度 5-20")
        String phone,

        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
        String email
) {
}
