package com.safespot.apicore.event.payload;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class EvacuationEntryCreatedPayload {
    private final Long entryId;
    private final Long shelterId;
    private final String nextStatus;
    private final Long recordedByAdminId;
    private final OffsetDateTime enteredAt;
}
