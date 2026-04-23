package com.safespot.apicore.event.payload;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
public class EvacuationEntryUpdatedPayload {
    private final Long entryId;
    private final Long shelterId;
    private final Long recordedByAdminId;
    private final OffsetDateTime updatedAt;
    private final List<String> changedFields;
}
