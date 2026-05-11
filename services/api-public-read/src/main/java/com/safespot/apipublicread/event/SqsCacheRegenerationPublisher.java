package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class SqsCacheRegenerationPublisher implements CacheRegenerationPublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final CacheKeyFamilyResolver resolver;
    private final CacheRegenerationPublishFailureRecorder failureRecorder;
    private final MeterRegistry meterRegistry;

    @Override
    public void publish(String cacheKey, CacheRegenerationReason reason, String endpoint) {
        Optional<String> family = resolver.resolve(cacheKey);
        if (family.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheKey={}, skipping SQS publish", cacheKey);
            return;
        }
        CacheRegenerationEnvelope envelope = CacheRegenerationEnvelope.build(cacheKey, family.get(), reason);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[CacheRegen] envelope serialization failed reason={} cacheFamily={} cacheKey={} endpoint={} traceId={} idempotencyKey={}: {}",
                    reason.value(), family.get(), cacheKey, endpoint, envelope.traceId(),
                    envelope.idempotencyKey(), e.getMessage(), e);
            meterRegistry.counter("api_read_cache_regen_publish_total",
                    "service", "api-public-read",
                    "endpoint", endpoint,
                    "result", "failure"
            ).increment();
            return;
        }
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] sent to SQS reason={} cacheFamily={} cacheKey={} endpoint={} traceId={} idempotencyKey={}",
                    reason.value(), family.get(), cacheKey, endpoint, envelope.traceId(), envelope.idempotencyKey());
            meterRegistry.counter("api_read_cache_regen_publish_total",
                    "service", "api-public-read",
                    "endpoint", endpoint,
                    "result", "success"
            ).increment();
        } catch (Exception e) {
            log.error("[CacheRegen] SQS send failed eventId={} eventType={} reason={} cacheFamily={} cacheKey={} endpoint={} traceId={} idempotencyKey={}: {}",
                    envelope.eventId(), envelope.eventType(), reason.value(), family.get(), cacheKey, endpoint,
                    envelope.traceId(), envelope.idempotencyKey(), e.getMessage());
            failureRecorder.record(envelope, body);
            meterRegistry.counter("api_read_cache_regen_publish_total",
                    "service", "api-public-read",
                    "endpoint", endpoint,
                    "result", "failure"
            ).increment();
        }
    }
}
