package com.isd.ticket.dto;

import java.time.LocalDateTime;

import com.isd.ticket.entity.Ticket;

/**
 * 工单对外视图（VO）。
 * 与实体的区别：不返回 version（乐观锁是内部实现细节，暴露给前端反而诱导前端传版本号伪造并发语义）。
 */
public record TicketResponse(
        Long id,
        String ticketNo,
        String title,
        String description,
        String category,
        String priority,
        String status,
        Long creatorId,
        Long assigneeId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getCategory(),
                ticket.getPriority(),
                ticket.getStatus(),
                ticket.getCreatorId(),
                ticket.getAssigneeId(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }
}
