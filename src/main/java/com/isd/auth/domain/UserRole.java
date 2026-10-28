package com.isd.auth.domain;

import java.util.Arrays;
import java.util.Locale;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;

/**
 * 角色枚举。
 * 存库时 MyBatis 默认按枚举名（name）写入 VARCHAR，读回时用 EnumTypeHandler 还原，
 * 好处是数据库里存的是可读字符串，出问题时肉眼能看懂，而不用去查 0/1/2 的映射表。
 */
public enum UserRole {

    EMPLOYEE,
    SUPPORT,
    ADMIN;

    public static UserRole of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "角色不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "非法角色: " + raw + "，允许值: " + Arrays.toString(values()));
        }
    }
}
