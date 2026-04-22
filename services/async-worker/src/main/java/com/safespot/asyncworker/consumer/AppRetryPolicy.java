package com.safespot.asyncworker.consumer;

import com.safespot.asyncworker.envelope.EventType;

import java.util.Map;
import java.util.Optional;

final class AppRetryPolicy {

    // SQS maxReceiveCount=5 기준에서 이 이벤트들만 앱 레벨로 더 낮게 제한
    private static final Map<EventType, Integer> MAX_RECEIVE_COUNT = Map.of(
        EventType.EvacuationEntryUpdated, 3,
        EventType.EnvironmentDataCollected, 3
    );

    private AppRetryPolicy() {}

    static Optional<Integer> maxReceiveCount(EventType eventType) {
        return Optional.ofNullable(MAX_RECEIVE_COUNT.get(eventType));
    }
}
