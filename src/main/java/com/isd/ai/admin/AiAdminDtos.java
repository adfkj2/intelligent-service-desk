package com.isd.ai.admin;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AiAdminDtos {
    private AiAdminDtos() { }

    public record SaveRequest(Long version, @NotBlank @Size(max=80) String name,
            @NotBlank @Pattern(regexp="DEEPSEEK|QWEN|OPENAI|CUSTOM") String provider,
            @NotBlank @Size(max=512) String baseUrl, @NotBlank @Size(max=128) String model,
            @Size(max=4096) String apiKey, @Pattern(regexp="[A-Z]{3}") String currency,
            @DecimalMin("0") @Digits(integer=12,fraction=8) BigDecimal inputPrice,
            @DecimalMin("0") @Digits(integer=12,fraction=8) BigDecimal cachedInputPrice,
            @DecimalMin("0") @Digits(integer=12,fraction=8) BigDecimal outputPrice) {
        @Override public String toString() { return "AiConfigurationSaveRequest[apiKey=REDACTED]"; }
    }
    public record VersionRequest(@NotNull @Positive Long version) { }
    public record RuntimeRequest(@NotNull @Min(0) Long version,
            @NotBlank @Pattern(regexp="MANAGED|DISABLED") String mode,
            Long configurationId, Long configurationVersion) { }
    public record ConfigResponse(Long id, Long version, String name, String provider, String baseUrl,
            String model, String keySuffix, String currency, BigDecimal inputPrice,
            BigDecimal cachedInputPrice, BigDecimal outputPrice, String testStatus, String testMessage,
            LocalDateTime testedAt, boolean archived, boolean current, LocalDateTime createdAt) { }
    public record RuntimeResponse(String mode, Long version, Long configurationId,
            Long configurationVersion, String provider, String model, boolean keyConfigured,
            boolean credentialsReady, String message) { }
    public record BalanceAmount(String currency, BigDecimal totalBalance, BigDecimal grantedBalance,
            BigDecimal toppedUpBalance) { }
    public record BalanceResponse(boolean supported, Boolean available, boolean stale,
            LocalDateTime checkedAt, LocalDateTime attemptedAt, String message, String consoleUrl,
            List<BalanceAmount> balances) { }
}
