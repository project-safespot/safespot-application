package com.safespot.apipublicread.event;

import java.time.Instant;
import java.util.UUID;

public record DisasterWarmupEnvelope(
        String eventType,
        String eventId,
        String occurredAt,
        String producer,
        String traceId,
        String idempotencyKey,
        DisasterWarmupPayload payload
) {
    private static final String EVENT_TYPE = "DisasterReadModelWarmupRequested";
    private static final String PRODUCER = "api-public-read";

    public static DisasterWarmupEnvelope build(int limit, boolean includeDetails) {
        Instant now = Instant.now();
        long windowStart = (now.getEpochSecond() / 30) * 30;
        String idempotencyKey = "disaster-warmup:all:" + windowStart;

        return new DisasterWarmupEnvelope(
                EVENT_TYPE,
                UUID.randomUUID().toString(),
                now.toString(),
                PRODUCER,
                UUID.randomUUID().toString(),
                idempotencyKey,
                new DisasterWarmupPayload(limit, includeDetails)
        );
    }
}
