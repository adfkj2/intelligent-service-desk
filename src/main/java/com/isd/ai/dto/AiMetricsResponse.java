package com.isd.ai.dto;

public record AiMetricsResponse(long answers, long feedbackCount, long helpfulCount,
                                long resolvedCount, long adoptedCount, long suggestionCount,
                                long helpfulFeedbackCount, long suggestionAdoptedCount) {
    public AiMetricsResponse(long answers, long feedbackCount, long helpfulCount,
                             long resolvedCount, long adoptedCount) {
        this(answers, feedbackCount, helpfulCount, resolvedCount, adoptedCount, 0, 0, 0);
    }
}
