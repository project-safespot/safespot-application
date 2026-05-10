package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirQualityDto(
        String stationName,
        Integer aqi,
        String grade,
        Integer pm10,
        String pm10Grade,
        Integer pm25,
        String pm25Grade,
        BigDecimal o3,
        String o3Grade,
        String measuredAt
) {
    public AirQualityDto(String stationName, Integer aqi, String grade, String measuredAt) {
        this(stationName, aqi, grade, null, null, null, null, null, null, measuredAt);
    }
}
