package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.EvacuationEntryUpdatedPayload;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvacuationEntryUpdatedHandler implements EventHandler {

    // 현재인원(COUNT)에 영향을 주는 유일한 필드
    private static final String ENTRY_STATUS_FIELD = "entryStatus";

    private final ShelterStatusService shelterStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.EvacuationEntryUpdated;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        EvacuationEntryUpdatedPayload payload = parsePayload(envelope);
        log.info("Handling EvacuationEntryUpdated: entryId={}, shelterId={}, changedFields={}, traceId={}",
            payload.entryId(), payload.shelterId(), payload.changedFields(), envelope.getTraceId());

        if (!payload.changedFields().contains(ENTRY_STATUS_FIELD)) {
            log.info("No occupancy-affecting field changed, no-op: entryId={}", payload.entryId());
            return;
        }

        shelterStatusService.recalculate(payload.shelterId());
    }

    private EvacuationEntryUpdatedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), EvacuationEntryUpdatedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse EvacuationEntryUpdated payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
