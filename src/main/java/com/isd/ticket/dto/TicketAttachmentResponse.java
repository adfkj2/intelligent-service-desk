package com.isd.ticket.dto;

import java.time.LocalDateTime;
import com.isd.ticket.entity.TicketAttachment;

public record TicketAttachmentResponse(Long id, Long ticketId, Long uploaderId, String uploaderName,
                                       String fileName, String contentType, long size, String visibility,
                                       LocalDateTime createdAt) {
    public static TicketAttachmentResponse from(TicketAttachment value) {
        return new TicketAttachmentResponse(value.getId(), value.getTicketId(), value.getUploaderId(),
                value.getUploaderName(), value.getFileName(), value.getContentType(), value.getSize(),
                value.getVisibility(), value.getCreatedAt());
    }
}
