package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvironmentDataCollectedPayload(
    String collectionType,
    String region,
    String completedAt,
    String timeWindow
) {}
