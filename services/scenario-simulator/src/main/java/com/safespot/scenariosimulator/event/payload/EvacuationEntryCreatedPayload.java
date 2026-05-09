package com.safespot.scenariosimulator.event.payload;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class EvacuationEntryCreatedPayload {

    private Long entryId;
    private Long shelterId;
    private String nextStatus;
    private OffsetDateTime enteredAt;
}
