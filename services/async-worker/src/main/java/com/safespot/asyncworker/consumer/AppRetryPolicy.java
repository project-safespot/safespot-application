package com.safespot.asyncworker.consumer;

import com.safespot.asyncworker.envelope.EventType;

import java.util.Map;
import java.util.Optional;

final class AppRetryPolicy {

    // SQS maxReceiveCount=5 기준과 일치. 앱 레벨 재시도 한도를 명시적으로 통일한다.
    private static final Map<EventType, Integer> MAX_RECEIVE_COUNT = Map.of(
        EventType.EvacuationEntryCreated,     5,
        EventType.EvacuationEntryExited,      5,
        EventType.EvacuationEntryUpdated,     5,
        EventType.ShelterUpdated,             5,
        EventType.DisasterDataCollected,      5,
        EventType.EnvironmentDataCollected,   5,
        EventType.CacheRegenerationRequested, 5
    );

    private AppRetryPolicy() {}

    static Optional<Integer> maxReceiveCount(EventType eventType) {
        return Optional.ofNullable(MAX_RECEIVE_COUNT.get(eventType));
    }
}
