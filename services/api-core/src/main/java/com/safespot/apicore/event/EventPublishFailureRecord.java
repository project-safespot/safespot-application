package com.safespot.apicore.event;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class EventPublishFailureRecord {
    private final String eventId;
    private final String eventType;
    private final String idempotencyKey;
    private final String traceId;
    private final String queueName;
    private final OffsetDateTime failedAt;
    private final int retryCount;
    private final String lastError;
    // Full serialized envelope JSON — sufficient for manual or automated replay.
    private final String replayableEnvelope;
}
