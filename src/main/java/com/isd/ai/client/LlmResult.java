package com.isd.ai.client;

/** Billing is collected once per raw HTTP response; model metadata stays internal. */
public record LlmResult(String text, String model, String responseId,
                        Long inputTokens, Long outputTokens, Long totalTokens) {
    public static LlmResult text(String text) {
        return new LlmResult(text, null, null, null, null, null);
    }
}
