package com.isd.common.time;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** DATETIME business values use the same zone as MySQL sessions and the deployed app. */
public final class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private BusinessTime() { }
    public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
}
