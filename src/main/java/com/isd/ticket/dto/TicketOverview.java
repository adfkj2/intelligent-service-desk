package com.isd.ticket.dto;

import java.util.Map;
import java.util.List;

public record TicketOverview(Map<String, Long> counts, Map<String, Long> queues,
                             Double averageResponseSeconds, Double averageResolutionSeconds,
                             Double averageFillDurationSeconds, double reopenRate,
                             List<SupportOption> supportWorkloads, long informationRequests) {
}
