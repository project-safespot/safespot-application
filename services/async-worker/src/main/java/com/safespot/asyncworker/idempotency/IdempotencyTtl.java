package com.safespot.asyncworker.idempotency;

import com.safespot.asyncworker.envelope.EventType;

import java.time.Duration;

public final class IdempotencyTtl {

    private IdempotencyTtl() {}

    // 각 이벤트의 Redis 출력 키 TTL + 여유분
    public static final Duration EVACUATION = Duration.ofMinutes(5);
    public static final Duration SHELTER    = Duration.ofMinutes(5);
    public static final Duration DISASTER   = Duration.ofMinutes(15);
    public static final Duration ENVIRONMENT = Duration.ofMinutes(130);

    public static Duration forEventType(EventType eventType) {
        return switch (eventType) {
            case EvacuationEntryCreated,
                 EvacuationEntryExited,
                 EvacuationEntryUpdated -> EVACUATION;
            case ShelterUpdated        -> SHELTER;
            case DisasterDataCollected -> DISASTER;
            case EnvironmentDataCollected -> ENVIRONMENT;
        };
    }
}
