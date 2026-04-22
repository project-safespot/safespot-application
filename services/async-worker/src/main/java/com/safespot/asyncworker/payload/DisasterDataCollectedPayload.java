package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DisasterDataCollectedPayload(
    String collectionType,
    String region,
    List<Long> affectedAlertIds,
    boolean hasExpiredAlerts,
    String completedAt
) {}
