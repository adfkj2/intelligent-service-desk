package com.isd.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationResponse(String conversationId, Long ticketId, List<Message> messages) {
    public record Message(String role, String content, Long answerId, LocalDateTime createdAt) {
    }
}
