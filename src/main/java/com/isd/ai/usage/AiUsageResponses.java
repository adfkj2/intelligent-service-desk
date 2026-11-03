package com.isd.ai.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AiUsageResponses {
    private AiUsageResponses() { }

    public record Cost(String currency, BigDecimal estimatedCost, long unknownCostCalls) { }
    public record Summary(long calls, long upstreamRequests, long failedCalls, long pendingCalls,
                          long unknownUsageCalls, long inputTokens, long cachedInputTokens,
                          long outputTokens, List<Cost> costs) { }
    public record Daily(LocalDate date, long calls, long upstreamRequests, long inputTokens,
                        long cachedInputTokens, long outputTokens, long failedCalls) { }
    public record User(Long userId, String username, String fullName, long calls, long upstreamRequests,
                       long inputTokens, long cachedInputTokens, long outputTokens, long failedCalls) { }
    public record Call(String id, Long userId, String username, String fullName, String purpose,
                       Long configurationId, Long configurationVersion, String provider,
                       String configurationName, String keySuffix, String model, String actualModel,
                       String currency, String businessOutcome, String upstreamOutcome,
                       long upstreamRequests, long inputTokens, long cachedInputTokens, long outputTokens,
                       boolean usageComplete, BigDecimal estimatedCost, LocalDateTime createdAt,
                       LocalDateTime completedAt, long durationMs) { }
}
