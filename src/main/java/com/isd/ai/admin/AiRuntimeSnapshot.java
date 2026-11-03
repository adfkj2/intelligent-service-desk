package com.isd.ai.admin;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Immutable credentials pinned for the lifetime of one business invocation. Never serialize it. */
public record AiRuntimeSnapshot(Long configurationId, Long configurationVersion, String provider,
                                String name, String baseUrl, String model, @JsonIgnore String apiKey, String keySuffix,
                                String currency, BigDecimal inputPrice, BigDecimal cachedInputPrice,
                                BigDecimal outputPrice) {
    @Override public String toString() {
        return "AiRuntimeSnapshot[configurationId=" + configurationId + ", version=" + configurationVersion
                + ", provider=" + provider + ", model=" + model + ", apiKey=REDACTED]";
    }
}
