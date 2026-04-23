package com.safespot.apicore.event;

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
    private final SqsEventPublisher sqsEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryCreatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        sqsEventPublisher.publish(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryExitedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        sqsEventPublisher.publish(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(EvacuationEntryUpdatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        sqsEventPublisher.publish(event.getEnvelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ShelterUpdatedSpringEvent event) {
        var payload = event.getEnvelope().getPayload();
        shelterCacheService.invalidateShelterCache(payload.getShelterId());
        shelterCacheService.invalidateShelterListCache(payload.getShelterType(), payload.getDisasterType());
        sqsEventPublisher.publish(event.getEnvelope());
    }
}
