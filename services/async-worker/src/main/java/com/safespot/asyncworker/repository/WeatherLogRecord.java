package com.safespot.asyncworker.repository;

public record WeatherLogRecord(
    int nx,
    int ny,
    double temperature,
    String weatherCondition,
    String forecastedAt
) {}
