package com.isd.common;

import com.isd.common.time.BusinessTime;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {
    @Test
    void businessTimeDoesNotDependOnHostTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Instant before = Instant.now();
            LocalDateTime value = BusinessTime.now();
            assertThat(value.atZone(BusinessTime.ZONE).toInstant()).isBetween(before, Instant.now());
            assertThat(BusinessTime.ZONE.getId()).isEqualTo("Asia/Shanghai");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
