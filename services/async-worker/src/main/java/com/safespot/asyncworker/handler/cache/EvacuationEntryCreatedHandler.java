package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.EvacuationEntryCreatedPayload;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvacuationEntryCreatedHandler implements EventHandler {

    private final ShelterStatusService shelterStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.EvacuationEntryCreated;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        EvacuationEntryCreatedPayload payload = parsePayload(envelope);
        log.info("Handling EvacuationEntryCreated: entryId={}, shelterId={}, traceId={}",
            payload.entryId(), payload.shelterId(), envelope.getTraceId());

        shelterStatusService.recalculate(payload.shelterId());
    }

    private EvacuationEntryCreatedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), EvacuationEntryCreatedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse EvacuationEntryCreated payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
