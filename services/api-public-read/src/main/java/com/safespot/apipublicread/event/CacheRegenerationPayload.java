package com.safespot.apipublicread.event;

public record CacheRegenerationPayload(
        String cacheKey,
        String cacheKeyFamily,
        String requestedAt,
        String reason,
        String schemaVersion
) {}
