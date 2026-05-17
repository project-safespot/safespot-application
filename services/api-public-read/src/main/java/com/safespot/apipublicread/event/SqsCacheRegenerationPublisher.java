package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.cache.PublicReadMetricRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;
import java.util.Map;
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
    private final PublicReadMetricRecorder metricRecorder;

    public SqsCacheRegenerationPublisher(
            SqsClient sqsClient,
            SqsQueueUrlProvider queueUrlProvider,
            ObjectMapper objectMapper,
            CacheKeyFamilyResolver familyResolver,
            CacheRegenerationRouteResolver routeResolver,
            CacheRegenerationEnvelopeFactory envelopeFactory,
            CacheRegenerationPublishFailureRecorder failureRecorder,
            MeterRegistry meterRegistry
    ) {
        this(sqsClient, queueUrlProvider, objectMapper, familyResolver, routeResolver,
                envelopeFactory, failureRecorder, meterRegistry, new PublicReadMetricRecorder(meterRegistry));
    }

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
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "failed");
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
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "published");
        } catch (Exception e) {
            log.error("[CacheRegen] SQS send failed cacheFamily={} cacheKey={} queueType={} envelopeType={} eventId={} endpoint={} traceId={} idempotencyKey={}: {}",
                    cacheFamily, cacheKey, queueType.label(), envelopeType,
                    envelope.eventId(), endpoint, envelope.traceId(), envelope.idempotencyKey(), e.getMessage());
            failureRecorder.record(envelope, body, queueType, envelopeType);
            recordMetric(cacheFamily, queueType.label(), envelopeType, reason.value(), endpoint, "failure");
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "failed");
        }
    }

    private static final Map<String, String> TARGET_TYPE_TO_FAMILY = Map.of(
            "SHELTER_STATUS", "shelter_status",
            "SHELTER_MAP_ITEMS", "shelter_map_item",
            "SHELTER_GEO_INDEX", "shelter_geo_index",
            "SHELTER_MAP_TILES", "shelter_map_tile"
    );

    @Override
    public void publishBatch(String targetType, List<Long> targetIds, CacheRegenerationReason reason, String endpoint) {
        if (targetIds == null || targetIds.isEmpty()) {
            log.warn("[CacheRegen] publishBatch called with empty targetIds: targetType={} endpoint={}", targetType, endpoint);
            return;
        }

        String cacheFamily = TARGET_TYPE_TO_FAMILY.get(targetType);
        if (cacheFamily == null) {
            log.warn("[CacheRegen] publishBatch unsupported targetType={} endpoint={}", targetType, endpoint);
            return;
        }

        Optional<CacheRegenerationRoute> routeOpt = routeResolver.resolve(cacheFamily);
        if (routeOpt.isEmpty()) {
            log.warn("[CacheRegen] publishBatch no queue route for cacheFamily={} targetType={}", cacheFamily, targetType);
            return;
        }
        QueueType queueType = routeOpt.get().queueType();
        String queueUrl = queueUrlProvider.get(queueType);

        CacheRegenerationEnvelope envelope = envelopeFactory.buildBatch(cacheFamily, targetType, targetIds, reason);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[CacheRegen] batch serialization failed targetType={} count={} endpoint={}: {}",
                    targetType, targetIds.size(), endpoint, e.getMessage(), e);
            return;
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] batch published cacheFamily={} targetType={} count={} endpoint={} traceId={} idempotencyKey={}",
                    cacheFamily, targetType, targetIds.size(), endpoint, envelope.traceId(), envelope.idempotencyKey());
            recordBatchMetric(cacheFamily, queueType.label(), reason.value(), endpoint, "success");
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "published");
        } catch (Exception e) {
            log.error("[CacheRegen] batch SQS send failed cacheFamily={} targetType={} count={} endpoint={} traceId={} idempotencyKey={}: {}",
                    cacheFamily, targetType, targetIds.size(), endpoint,
                    envelope.traceId(), envelope.idempotencyKey(), e.getMessage());
            failureRecorder.record(envelope, body, queueType, "batch");
            recordBatchMetric(cacheFamily, queueType.label(), reason.value(), endpoint, "failure");
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "failed");
        }
    }

    @Override
    public void publishTarget(String targetType, CacheRegenerationReason reason, String endpoint) {
        String cacheFamily = TARGET_TYPE_TO_FAMILY.get(targetType);
        if (cacheFamily == null) {
            log.warn("[CacheRegen] publishTarget unsupported targetType={} endpoint={}", targetType, endpoint);
            return;
        }

        Optional<CacheRegenerationRoute> routeOpt = routeResolver.resolve(cacheFamily);
        if (routeOpt.isEmpty()) {
            log.warn("[CacheRegen] publishTarget no queue route for cacheFamily={} targetType={}", cacheFamily, targetType);
            return;
        }
        QueueType queueType = routeOpt.get().queueType();
        String queueUrl = queueUrlProvider.get(queueType);

        CacheRegenerationEnvelope envelope = envelopeFactory.buildTarget(cacheFamily, targetType, reason);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[CacheRegen] target serialization failed targetType={} endpoint={}: {}",
                    targetType, endpoint, e.getMessage(), e);
            return;
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] target published cacheFamily={} targetType={} endpoint={} traceId={} idempotencyKey={}",
                    cacheFamily, targetType, endpoint, envelope.traceId(), envelope.idempotencyKey());
            recordBatchMetric(cacheFamily, queueType.label(), reason.value(), endpoint, "success");
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "published");
        } catch (Exception e) {
            log.error("[CacheRegen] target SQS send failed cacheFamily={} targetType={} endpoint={} traceId={} idempotencyKey={}: {}",
                    cacheFamily, targetType, endpoint, envelope.traceId(), envelope.idempotencyKey(), e.getMessage());
            failureRecorder.record(envelope, body, queueType, "batch");
            recordBatchMetric(cacheFamily, queueType.label(), reason.value(), endpoint, "failure");
            metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "failed");
        }
    }

    private void recordBatchMetric(String cacheFamily, String queue, String reason, String endpoint, String result) {
        meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", cacheFamily,
                "queue", queue,
                "envelope", "batch",
                "reason", reason,
                "result", result
        ).increment();
        meterRegistry.counter("api_read_cache_regen_publish_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", result
        ).increment();
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
