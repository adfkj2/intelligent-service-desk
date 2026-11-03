package com.isd.ai.entity;

public record AiConversation(String id, Long userId, Long ticketId, String purpose, String accessRole, int version) {
}
