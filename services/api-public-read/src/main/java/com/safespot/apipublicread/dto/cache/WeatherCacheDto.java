package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherCacheDto(
        int nx,
        int ny,
        double temperature,
        String weatherCondition,
        String precipitationType,
        String precipitation,
        BigDecimal windSpeed,
        Integer humidity,
        String forecastedAt
) {}
