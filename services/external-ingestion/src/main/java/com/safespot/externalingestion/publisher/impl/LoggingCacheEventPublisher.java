package com.safespot.externalingestion.publisher.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.CacheRefreshEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingCacheEventPublisher implements CacheEventPublisher {

    private final ObjectMapper objectMapper;

    @Override
    public void publish(CacheRefreshEvent event, String queueTarget) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            log.info("[CacheEvent] eventType={} idempotencyKey={} queue={} payload={}",
                event.getEventType(), event.getIdempotencyKey(), queueTarget, payload);
        } catch (JsonProcessingException e) {
            log.error("[CacheEvent] failed to serialize event eventType={}", event.getEventType(), e);
        }
    }
}
