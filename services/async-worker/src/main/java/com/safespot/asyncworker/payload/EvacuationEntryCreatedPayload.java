package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvacuationEntryCreatedPayload(
    Long entryId,
    Long shelterId,
    String nextStatus,
    Long recordedByAdminId,
    String enteredAt
) {}
