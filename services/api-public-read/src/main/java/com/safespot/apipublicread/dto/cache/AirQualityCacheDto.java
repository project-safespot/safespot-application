package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AirQualityCacheDto(
        String stationName,
        int aqi,
        String grade,
        Integer pm10,
        String pm10Grade,
        Integer pm25,
        String pm25Grade,
        BigDecimal o3,
        String o3Grade,
        String measuredAt
) {}
