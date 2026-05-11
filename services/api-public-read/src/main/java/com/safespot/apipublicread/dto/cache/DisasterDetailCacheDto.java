package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DisasterDetailCacheDto(
        int schemaVersion,
        Long alertId,
        String disasterType,
        String rawType,
        String messageCategory,
        String level,
        Integer levelRank,
        String region,
        String issuedAt,
        String expiredAt,
        String message,
        String source,
        Boolean isInScope
) {}
