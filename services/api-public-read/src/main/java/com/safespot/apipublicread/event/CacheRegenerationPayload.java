package com.safespot.apipublicread.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CacheRegenerationPayload(
        String cacheKey,
        String cacheKeyFamily,
        String requestedAt,
        String reason,
        String schemaVersion,
        String targetType,
        List<Long> targetIds
) {}
