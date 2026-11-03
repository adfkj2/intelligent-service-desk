package com.isd.ai.admin;

import java.time.LocalDateTime;

public class AiBalanceRow {
    public String balanceJson;
    public Boolean available;
    public LocalDateTime checkedAt;
    public LocalDateTime attemptedAt;
    public String lastError;
}
