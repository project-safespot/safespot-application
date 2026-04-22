package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.EvacuationEntryExitedPayload;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("cache-worker")
@Slf4j
@Component
@RequiredArgsConstructor
public class EvacuationEntryExitedHandler implements EventHandler {

    private final ShelterStatusService shelterStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.EvacuationEntryExited;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        EvacuationEntryExitedPayload payload = parsePayload(envelope);
        log.info("Handling EvacuationEntryExited: entryId={}, shelterId={}, traceId={}",
            payload.entryId(), payload.shelterId(), envelope.getTraceId());

        shelterStatusService.recalculate(payload.shelterId());
    }

    private EvacuationEntryExitedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), EvacuationEntryExitedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse EvacuationEntryExited payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
