package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherAlertDto(
        String region,
        Integer nx,
        Integer ny,
        Double temperature,
        String weatherCondition,
        String forecastedAt
) {}
