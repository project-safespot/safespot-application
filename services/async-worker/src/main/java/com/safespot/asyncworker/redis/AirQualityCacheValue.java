package com.safespot.asyncworker.redis;

public record AirQualityCacheValue(
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
    public AirQualityCacheValue(String stationName, int aqi, String grade, String measuredAt) {
        this(stationName, aqi, grade, null, null, null, null, null, null, measuredAt);
    }
}
