package com.isd.ticket.dto;

import java.time.LocalDateTime;

public record TicketReplyResponse(Long id, Long ticketId, Long authorId, String authorName,
                                  String content, String visibility, LocalDateTime createdAt) {
}
