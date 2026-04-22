package com.safespot.asyncworker.redis;

public record DisasterActiveItem(
    Long alertId,
    String disasterType,
    String region,
    String level,
    String issuedAt,
    String expiredAt
) {}
