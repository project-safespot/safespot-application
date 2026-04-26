package com.safespot.asyncworker.redis;

import java.util.List;

public record WeatherAlertCacheValue(
    int schemaVersion,
    String status,
    List<Object> alerts
) {
    // weather_alert_log is not in MVP schema; always set as no_data placeholder
    public static WeatherAlertCacheValue noData() {
        return new WeatherAlertCacheValue(1, "no_data", List.of());
    }
}
