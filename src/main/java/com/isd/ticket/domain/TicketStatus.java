package com.isd.ticket.domain;

import java.util.Arrays;
import java.util.Locale;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;

/**
 * 工单状态。
 * OPEN → IN_PROGRESS → RESOLVED → CLOSED，只允许前进，不允许跳级或回退（reopen 列为 v2 扩展点）。
 */
public enum TicketStatus {

    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    public static TicketStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "状态不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "非法状态: " + raw + "，允许值: " + Arrays.toString(values()));
        }
    }
}
