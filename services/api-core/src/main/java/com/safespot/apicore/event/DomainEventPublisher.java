package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.cache.ShelterCacheService;
import com.safespot.apicore.event.springevent.EvacuationEntryCreatedSpringEvent;
import com.safespot.apicore.event.springevent.EvacuationEntryExitedSpringEvent;
import com.safespot.apicore.event.springevent.EvacuationEntryUpdatedSpringEvent;
import com.safespot.apicore.event.springevent.ShelterUpdatedSpringEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisher {

    private final ShelterCacheService shelterCacheService;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryCreatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        publishLog(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryExitedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        publishLog(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryUpdatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        publishLog(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ShelterUpdatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        publishLog(event.getEnvelope());
    }

    private void publishLog(EventEnvelope<?> envelope) {
        try {
            log.info("[EVENT] type={} idempotencyKey={} eventId={}",
                    envelope.getEventType(),
                    envelope.getIdempotencyKey(),
                    envelope.getEventId());
        } catch (Exception e) {
            log.error("[EVENT] publish failed eventType={}", envelope.getEventType(), e);
        }
    }
}
