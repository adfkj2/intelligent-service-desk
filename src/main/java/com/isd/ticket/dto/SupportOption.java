package com.isd.ticket.dto;

import java.util.List;

public record SupportOption(Long userId, String name, long activeTickets, List<String> skills) {
}
