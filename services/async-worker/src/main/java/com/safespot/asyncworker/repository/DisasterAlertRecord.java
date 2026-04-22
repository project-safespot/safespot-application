package com.safespot.asyncworker.repository;

public record DisasterAlertRecord(
    Long alertId,
    String disasterType,
    String region,
    String level,
    String message,
    String source,
    String issuedAt,
    String expiredAt
) {}
