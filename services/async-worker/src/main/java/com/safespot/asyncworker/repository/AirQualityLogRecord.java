package com.safespot.asyncworker.repository;

public record AirQualityLogRecord(
    String stationName,
    int aqi,
    String grade,
    Integer pm10,
    String pm10Grade,
    Integer pm25,
    String pm25Grade,
    java.math.BigDecimal o3,
    String o3Grade,
    String measuredAt
) {
    public AirQualityLogRecord(String stationName, int aqi, String grade, String measuredAt) {
        this(stationName, aqi, grade, null, null, null, null, null, null, measuredAt);
    }
}
