package com.safespot.asyncworker.handler.cache;

import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile({"cache-worker", "async-worker"})
@Slf4j
@Component
@RequiredArgsConstructor
public class ShelterStatusWarmupRequestedHandler implements EventHandler {

    private final ShelterStatusService shelterStatusService;

    @Override
    public EventType supportedEventType() {
        return EventType.ShelterStatusWarmupRequested;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        log.info("Handling ShelterStatusWarmupRequested: traceId={}", envelope.getTraceId());
        int count = shelterStatusService.warmUpAll();
        log.info("ShelterStatusWarmupRequested completed: count={}, traceId={}", count, envelope.getTraceId());
    }
}
