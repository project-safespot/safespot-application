package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherAlertCacheDto(
        int schemaVersion,
        String status,
        List<Object> alerts
) {}
