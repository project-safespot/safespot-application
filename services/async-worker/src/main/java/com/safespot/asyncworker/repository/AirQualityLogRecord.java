package com.safespot.asyncworker.repository;

public record AirQualityLogRecord(
    String stationName,
    int aqi,
    String grade,
    String measuredAt
) {}
