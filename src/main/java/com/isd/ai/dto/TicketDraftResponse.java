package com.isd.ai.dto;

import java.util.List;

public record TicketDraftResponse(String title, String description, String category, String priority,
        List<String> missingInformation, List<String> steps, List<KnowledgeReference> references,
        String source, Long answerId, String conversationId) {
}
