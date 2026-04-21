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
    private final int version;
    private final String producer;
    private final String traceId;
    private final String idempotencyKey;
    private final T payload;

    public static <T> EventEnvelope<T> of(String eventType, String idempotencyKey, T payload) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .occurredAt(OffsetDateTime.now())
                .version(1)
                .producer("api-core")
                .traceId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .build();
    }
}
