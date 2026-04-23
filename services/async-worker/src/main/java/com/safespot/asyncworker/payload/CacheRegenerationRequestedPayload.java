package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheRegenerationRequestedPayload(
    String cacheKey,
    String requestedAt
) {}
