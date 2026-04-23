package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirQualityDto(
        String stationName,
        Integer aqi,
        String grade,
        String measuredAt
) {}
