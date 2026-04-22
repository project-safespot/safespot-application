package com.safespot.asyncworker.redis;

public record DisasterDetailCacheValue(
    Long alertId,
    String disasterType,
    String region,
    String level,
    String message,
    String source,
    String issuedAt,
    String expiredAt
) {}
