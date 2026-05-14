package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.DisasterReadModelWarmupPayload;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// readmodel-worker: ReadModelWorkerHandler Lambda (readmodel-refresh queue 전용)
// async-worker: AsyncWorkerHandler Lambda (all queues 통합)
// DisasterDataCollectedHandler와 동일한 profile 패턴을 따른다.
@Profile({"readmodel-worker", "async-worker"})
@Slf4j
@Component
@RequiredArgsConstructor
public class DisasterReadModelWarmupRequestedHandler implements EventHandler {

    private final DisasterReadModelService disasterReadModelService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.DisasterReadModelWarmupRequested;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        DisasterReadModelWarmupPayload payload = parsePayload(envelope);
        log.info("Handling DisasterReadModelWarmupRequested: limit={}, includeDetails={}, traceId={}",
                payload.limit(), payload.includeDetails(), envelope.getTraceId());
        disasterReadModelService.warmupAll(payload.limit(), payload.includeDetails());
        log.info("DisasterReadModelWarmupRequested completed: traceId={}", envelope.getTraceId());
    }

    private DisasterReadModelWarmupPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), DisasterReadModelWarmupPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                    "Failed to parse DisasterReadModelWarmupRequested payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
