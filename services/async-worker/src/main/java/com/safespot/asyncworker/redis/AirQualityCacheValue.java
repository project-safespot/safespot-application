package com.safespot.asyncworker.redis;

public record AirQualityCacheValue(
    String stationName,
    int aqi,
    String grade,
    String measuredAt
) {}
