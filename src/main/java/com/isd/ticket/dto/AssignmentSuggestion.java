package com.isd.ticket.dto;

import java.util.List;

public record AssignmentSuggestion(Long userId, String name, long activeTickets, List<String> skills,
                                   String reason) {
}
