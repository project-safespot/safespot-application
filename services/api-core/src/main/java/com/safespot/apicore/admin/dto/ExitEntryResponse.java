package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ExitEntryResponse {
    private final Long entryId;
    private final String entryStatus;
    private final OffsetDateTime exitedAt;
}
