package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class UpdateShelterResponse {
    private final Long shelterId;
    private final OffsetDateTime updatedAt;
}
