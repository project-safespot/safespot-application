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

    /**
     * eventId를 idempotencyKey에 포함해야 하는 이벤트용 (EvacuationEntryUpdated, ShelterUpdated 등).
     * 생성된 eventId를 idempotencyKey prefix에 붙여 worker dedup 기준으로 사용한다.
     */
    public static <T> EventEnvelope<T> ofWithEventId(
            String eventType, String idempotencyKeyPrefix, T payload) {
        String eventId = UUID.randomUUID().toString();
        return EventEnvelope.<T>builder()
                .eventId(eventId)
                .eventType(eventType)
                .occurredAt(OffsetDateTime.now())
                .version(1)
                .producer("api-core")
                .traceId(UUID.randomUUID().toString())
                .idempotencyKey(idempotencyKeyPrefix + eventId)
                .payload(payload)
                .build();
    }
}
