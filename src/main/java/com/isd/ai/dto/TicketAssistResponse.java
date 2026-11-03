package com.isd.ai.dto;

import java.util.List;

public record TicketAssistResponse(String summary, List<String> steps, String replyDraft,
        List<SimilarTicket> similarTickets, List<KnowledgeReference> references, String source, Long answerId) {
    public record SimilarTicket(Long id, String ticketNo, String title, String solution) {
    }
}
