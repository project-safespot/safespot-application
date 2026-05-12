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
    private final SqsQueueUrlProvider queueUrlProvider;
    private final ObjectMapper objectMapper;
    private final CacheKeyFamilyResolver familyResolver;
    private final CacheRegenerationRouteResolver routeResolver;
    private final CacheRegenerationEnvelopeFactory envelopeFactory;
    private final CacheRegenerationPublishFailureRecorder failureRecorder;
    private final MeterRegistry meterRegistry;

    @Override
    public void publish(String cacheKey, CacheRegenerationReason reason, String endpoint) {
        Optional<String> familyOpt = familyResolver.resolve(cacheKey);
        if (familyOpt.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheKey={} endpoint={} reason={}: no family mapping",
                    cacheKey, endpoint, reason.value());
            recordUnsupportedMetric("unknown", reason.value());
            return;
        }
        String cacheFamily = familyOpt.get();

        Optional<CacheRegenerationRoute> routeOpt = routeResolver.resolve(cacheFamily);
        if (routeOpt.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheFamily={} cacheKey={} endpoint={} reason={}: no queue route",
                    cacheFamily, cacheKey, endpoint, reason.value());
            recordUnsupportedMetric(cacheFamily, reason.value());
            return;
        }
        CacheRegenerationRoute route = routeOpt.get();
        QueueType queueType = route.queueType();
        String envelopeType = route.envelopeType();
        String queueUrl = queueUrlProvider.get(queueType);

        CacheRegenerationEnvelope envelope = envelopeFactory.build(queueType, cacheKey, cacheFamily, reason);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[CacheRegen] serialization failed cacheFamily={} cacheKey={} queueType={} envelopeType={} endpoint={} traceId={} idempotencyKey={}: {}",
                    cacheFamily, cacheKey, queueType.label(), envelopeType, endpoint,
                    envelope.traceId(), envelope.idempotencyKey(), e.getMessage(), e);
            recordMetric(cacheFamily, queueType.label(), envelopeType, reason.value(), endpoint, "failure");
            return;
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] published cacheFamily={} cacheKey={} queueType={} envelopeType={} endpoint={} traceId={} idempotencyKey={}",
                    cacheFamily, cacheKey, queueType.label(), envelopeType, endpoint,
                    envelope.traceId(), envelope.idempotencyKey());
            recordMetric(cacheFamily, queueType.label(), envelopeType, reason.value(), endpoint, "success");
        } catch (Exception e) {
            log.error("[CacheRegen] SQS send failed cacheFamily={} cacheKey={} queueType={} envelopeType={} eventId={} endpoint={} traceId={} idempotencyKey={}: {}",
                    cacheFamily, cacheKey, queueType.label(), envelopeType,
                    envelope.eventId(), endpoint, envelope.traceId(), envelope.idempotencyKey(), e.getMessage());
            failureRecorder.record(envelope, body, queueType, envelopeType);
            recordMetric(cacheFamily, queueType.label(), envelopeType, reason.value(), endpoint, "failure");
        }
    }

    private void recordMetric(String cacheFamily, String queue, String envelopeType,
                               String reason, String endpoint, String result) {
        meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", cacheFamily,
                "queue", queue,
                "envelope", envelopeType,
                "reason", reason,
                "result", result
        ).increment();
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", "api-public-read",
                "cache", cacheFamily,
                "reason", reason,
                "result", result
        ).increment();
        meterRegistry.counter("api_read_cache_regen_publish_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", result
        ).increment();
    }

    private void recordUnsupportedMetric(String cacheFamily, String reason) {
        meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", cacheFamily,
                "queue", "unknown",
                "envelope", "unknown",
                "reason", reason,
                "result", "unsupported"
        ).increment();
    }
}
