package com.safespot.asyncworker.redis;

public record WeatherCacheValue(
    int nx,
    int ny,
    double temperature,
    String weatherCondition,
    String forecastedAt
) {}
