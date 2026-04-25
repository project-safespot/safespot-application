package com.safespot.externalingestion.publisher.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.IngestionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SQS가 비활성화된 환경에서만 등록되는 log-only fallback publisher.
 * ingestion.sqs.enabled=true이면 bean 미등록 → 시작 시 NoSuchBeanDefinitionException으로 즉시 실패.
 */
@ConditionalOnProperty(name = "ingestion.sqs.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingCacheEventPublisher implements CacheEventPublisher {

    private final ObjectMapper objectMapper;

    @Override
    public void publish(IngestionEvent event, String logicalQueueName) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            log.warn("[IngestionEvent][NO_QUEUE] eventType={} idempotencyKey={} queue={} payload={}",
                event.getEventType(), event.getIdempotencyKey(), logicalQueueName, payload);
        } catch (JsonProcessingException e) {
            log.error("[IngestionEvent][NO_QUEUE] serialization failed — " +
                    "eventId={} eventType={} idempotencyKey={} traceId={}",
                event.getEventId(), event.getEventType(), event.getIdempotencyKey(), event.getTraceId(), e);
        }
    }
}
