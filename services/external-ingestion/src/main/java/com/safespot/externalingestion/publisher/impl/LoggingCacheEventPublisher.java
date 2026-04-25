package com.safespot.externalingestion.publisher.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.IngestionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingCacheEventPublisher implements CacheEventPublisher {

    private final ObjectMapper objectMapper;
    private final boolean sqsEnabled;

    public LoggingCacheEventPublisher(ObjectMapper objectMapper,
                                      @Value("${ingestion.sqs.enabled:false}") boolean sqsEnabled) {
        this.objectMapper = objectMapper;
        this.sqsEnabled = sqsEnabled;
    }

    @Override
    public void publish(IngestionEvent event, String logicalQueueName) {
        if (sqsEnabled) {
            throw new IllegalStateException(
                "SQS is enabled but no SQS publisher is configured. " +
                "eventType=" + event.getEventType() +
                " idempotencyKey=" + event.getIdempotencyKey());
        }
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
