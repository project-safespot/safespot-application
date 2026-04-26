package com.safespot.asyncworker.redis;

// disaster:messages:recent:seoul, disaster:message:core:seoul, disaster:messages:list:seoul 의 개별 항목
// redis-key.md §2.1 공통 Payload 계약 기준
public record DisasterMessageItem(
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
