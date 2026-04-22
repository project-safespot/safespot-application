package com.safespot.asyncworker.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelterUpdatedPayload(
    Long shelterId,
    Long recordedByAdminId,
    String updatedAt,
    List<String> changedFields
) {
    public ShelterUpdatedPayload {
        changedFields = (changedFields != null) ? changedFields : List.of();
    }
}
