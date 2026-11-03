package com.isd.ai.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persistence-only row; never returned by an HTTP handler. */
public class AiConfigurationRow {
    public Long id;
    public Long latestVersion;
    public Long version;
    public String name;
    public String provider;
    public String baseUrl;
    public String model;
    public String keyCiphertext;
    public String keySuffix;
    public String currency;
    public BigDecimal inputPrice;
    public BigDecimal cachedInputPrice;
    public BigDecimal outputPrice;
    public String testStatus;
    public String testMessage;
    public LocalDateTime testedAt;
    public Boolean archived;
    public Long createdBy;
    public LocalDateTime createdAt;
    @Override public String toString() { return "AiConfigurationRow[id=" + id + ", secret=REDACTED]"; }
}
