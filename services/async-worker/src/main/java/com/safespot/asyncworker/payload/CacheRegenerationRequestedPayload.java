package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheRegenerationRequestedPayload(
    String cacheKey,
    String cacheKeyFamily,
    String requestedAt,
    String reason,
    Integer schemaVersion
) {}
