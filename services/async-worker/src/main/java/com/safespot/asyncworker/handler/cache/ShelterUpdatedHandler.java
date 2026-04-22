package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.ShelterUpdatedPayload;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShelterUpdatedHandler implements EventHandler {

    // 이 두 필드 변경 시에만 현재인원·혼잡도 재계산
    private static final List<String> RECALC_TRIGGER_FIELDS = List.of("capacityTotal", "shelterStatus");

    private final ShelterStatusService shelterStatusService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.ShelterUpdated;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        ShelterUpdatedPayload payload = parsePayload(envelope);
        log.info("Handling ShelterUpdated: shelterId={}, changedFields={}, traceId={}",
            payload.shelterId(), payload.changedFields(), envelope.getTraceId());

        boolean needsRecalculation = payload.changedFields().stream()
            .anyMatch(RECALC_TRIGGER_FIELDS::contains);

        if (!needsRecalculation) {
            log.info("No recalculation-triggering field changed, no-op: shelterId={}", payload.shelterId());
            return;
        }

        // Redis 이전 상태 비교 없음 — changedFields 통과 후 항상 RDS 재계산
        shelterStatusService.recalculate(payload.shelterId());
    }

    private ShelterUpdatedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), ShelterUpdatedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse ShelterUpdated payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
