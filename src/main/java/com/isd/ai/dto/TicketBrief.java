package com.isd.ai.dto;

import java.time.LocalDateTime;

import com.isd.ticket.dto.TicketResponse;

/**
 * 给模型看的工单摘要。
 * 只保留回答"我的工单进展"必需的字段：字段越多，token 越贵、模型越容易抓错重点，
 * 而且 description 里可能有用户的隐私信息，没必要整段塞给第三方模型。
 */
public record TicketBrief(
        Long id,
        String ticketNo,
        String title,
        String status,
        String priority,
        Long assigneeId,
        LocalDateTime updatedAt
) {

    public static TicketBrief from(TicketResponse ticket) {
        return new TicketBrief(ticket.id(), ticket.ticketNo(), ticket.title(), ticket.status(),
                ticket.priority(), ticket.assigneeId(), ticket.updatedAt());
    }
}
