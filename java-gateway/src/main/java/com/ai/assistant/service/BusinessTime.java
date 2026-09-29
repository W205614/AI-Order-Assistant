package com.ai.assistant.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Legacy DATETIME columns and API local timestamps use the restaurant's time zone. */
public final class BusinessTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessTime() { }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
