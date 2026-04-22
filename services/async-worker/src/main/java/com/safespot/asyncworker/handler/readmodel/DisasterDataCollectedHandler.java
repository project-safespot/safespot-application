package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.DisasterDataCollectedPayload;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("readmodel-worker")
@Slf4j
@Component
@RequiredArgsConstructor
public class DisasterDataCollectedHandler implements EventHandler {

    private final DisasterReadModelService disasterReadModelService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.DisasterDataCollected;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        DisasterDataCollectedPayload payload = parsePayload(envelope);
        log.info("Handling DisasterDataCollected: collectionType={}, region={}, hasExpiredAlerts={}, traceId={}",
            payload.collectionType(), payload.region(), payload.hasExpiredAlerts(), envelope.getTraceId());

        disasterReadModelService.rebuild(payload);
    }

    private DisasterDataCollectedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), DisasterDataCollectedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse DisasterDataCollected payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
