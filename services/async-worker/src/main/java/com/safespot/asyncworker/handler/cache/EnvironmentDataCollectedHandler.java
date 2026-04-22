package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.EnvironmentDataCollectedPayload;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("cache-worker")
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvironmentDataCollectedHandler implements EventHandler {

    private final EnvironmentCacheService environmentCacheService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.EnvironmentDataCollected;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        EnvironmentDataCollectedPayload payload = parsePayload(envelope);
        log.info("Handling EnvironmentDataCollected: collectionType={}, region={}, timeWindow={}, traceId={}",
            payload.collectionType(), payload.region(), payload.timeWindow(), envelope.getTraceId());

        environmentCacheService.rebuild(payload);
    }

    private EnvironmentDataCollectedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), EnvironmentDataCollectedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse EnvironmentDataCollected payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
