package com.isd.ticket.domain;

import java.util.Arrays;
import java.util.Locale;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;

/**
 * 状态机迁移白名单：每个动作自带 from/to，不在枚举里的迁移一律不存在。
 *
 * 为什么用枚举而不是 if/else 或状态机框架：
 * 1) 迁移规则集中在一处，加一个动作只需改这里，不需要满项目搜索 `if (status == ...)`；
 * 2) 白名单天然可测：遍历所有 (status, action) 组合断言允许/拒绝；
 * 3) 4 个状态引入 Spring StateMachine 是过度设计，面试时讲清"为什么不用"比堆框架更加分。
 */
public enum TicketAction {

    CLAIM(TicketStatus.OPEN, TicketStatus.IN_PROGRESS),
    ASSIGN(TicketStatus.OPEN, TicketStatus.IN_PROGRESS),
    RESOLVE(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED),
    CLOSE(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final TicketStatus from;
    private final TicketStatus to;

    TicketAction(TicketStatus from, TicketStatus to) {
        this.from = from;
        this.to = to;
    }

    public TicketStatus from() {
        return from;
    }

    public TicketStatus to() {
        return to;
    }

    public boolean allowedFrom(TicketStatus status) {
        return this.from == status;
    }

    public static TicketAction of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "action 不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "非法动作: " + raw + "，允许值: " + Arrays.toString(values()));
        }
    }
}
