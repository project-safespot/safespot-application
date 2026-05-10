package com.safespot.asyncworker.repository;

public record WeatherLogRecord(
    int nx,
    int ny,
    double temperature,
    String weatherCondition,
    String precipitationType,
    String precipitation,
    java.math.BigDecimal windSpeed,
    Integer humidity,
    String forecastedAt
) {
    public WeatherLogRecord(int nx, int ny, double temperature, String weatherCondition, String forecastedAt) {
        this(nx, ny, temperature, weatherCondition, null, null, null, null, forecastedAt);
    }
}
