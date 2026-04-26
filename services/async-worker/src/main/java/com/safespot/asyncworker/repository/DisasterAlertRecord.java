package com.safespot.asyncworker.repository;

public record DisasterAlertRecord(
    Long alertId,
    String disasterType,
    String rawType,
    String messageCategory,
    String region,
    String level,
    Integer levelRank,
    String message,
    String source,
    String issuedAt,
    String expiredAt,
    Boolean isInScope
) {}
