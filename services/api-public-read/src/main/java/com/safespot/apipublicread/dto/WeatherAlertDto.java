package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherAlertDto(
        String region,
        Integer nx,
        Integer ny,
        Double temperature,
        String weatherCondition,
        String precipitationType,
        String precipitation,
        BigDecimal windSpeed,
        Integer humidity,
        String forecastedAt
) {
    public WeatherAlertDto(String region, Integer nx, Integer ny, Double temperature,
                           String weatherCondition, String forecastedAt) {
        this(region, nx, ny, temperature, weatherCondition, null, null, null, null, forecastedAt);
    }
}
