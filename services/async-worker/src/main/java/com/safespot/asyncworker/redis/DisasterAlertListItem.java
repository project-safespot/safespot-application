package com.safespot.asyncworker.redis;

public record DisasterAlertListItem(
    Long alertId,
    String level,
    String issuedAt,
    String expiredAt
) {}
