package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class UpdateEntryResponse {
    private final Long entryId;
    private final OffsetDateTime updatedAt;
}
