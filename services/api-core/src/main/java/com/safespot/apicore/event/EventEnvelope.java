package com.safespot.apicore.event;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class EventEnvelope<T> {

    private final String eventId;
    private final String eventType;
    private final OffsetDateTime occurredAt;
    private final String producer;
    private final String traceId;
    private final String idempotencyKey;
    private final T payload;

    public static <T> EventEnvelope<T> of(String eventType, String idempotencyKey, T payload) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .occurredAt(OffsetDateTime.now())
                .producer("api-core")
                .traceId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .build();
    }

    public static <T> EventEnvelope<T> ofWithEventId(
            String eventType, String idempotencyKeyPrefix, T payload) {
        String eventId = UUID.randomUUID().toString();
        return EventEnvelope.<T>builder()
                .eventId(eventId)
                .eventType(eventType)
                .occurredAt(OffsetDateTime.now())
                .producer("api-core")
                .traceId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKeyPrefix + eventId)
                .payload(payload)
                .build();
    }
}
