package com.safespot.apipublicread.event;

import com.safespot.apipublicread.cache.PublicReadMetricRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "safespot.cache-regeneration.publisher-mode",
        havingValue = "log",
        matchIfMissing = true
)
public class LogOnlyCacheRegenerationPublisher implements CacheRegenerationPublisher {

    private final CacheKeyFamilyResolver resolver;
    private final MeterRegistry meterRegistry;
    private final PublicReadMetricRecorder metricRecorder;

    @Autowired
    public LogOnlyCacheRegenerationPublisher(CacheKeyFamilyResolver resolver, MeterRegistry meterRegistry,
                                             PublicReadMetricRecorder metricRecorder) {
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
        this.metricRecorder = metricRecorder;
    }

    LogOnlyCacheRegenerationPublisher(CacheKeyFamilyResolver resolver, MeterRegistry meterRegistry) {
        this(resolver, meterRegistry, new PublicReadMetricRecorder(meterRegistry));
    }

    private static final Map<String, String> TARGET_TYPE_TO_FAMILY = Map.of(
            "SHELTER_STATUS", "shelter_status",
            "SHELTER_MAP_ITEMS", "shelter_map_item",
            "SHELTER_GEO_INDEX", "shelter_geo_index",
            "SHELTER_MAP_TILES", "shelter_map_tile"
    );

    @Override
    public void publish(String cacheKey, CacheRegenerationReason reason, String endpoint) {
        Optional<String> family = resolver.resolve(cacheKey);
        if (family.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheKey={}, skipping publish", cacheKey);
            return;
        }
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(cacheKey, family.get(), reason);
        log.info("[CacheRegen] eventType={} eventId={} occurredAt={} producer={} reason={} cacheFamily={} cacheKey={} endpoint={} traceId={} idempotencyKey={} " +
                        "payload.requestedAt={} payload.schemaVersion={}",
                e.eventType(), e.eventId(), e.occurredAt(), e.producer(), reason.value(), family.get(), cacheKey, endpoint,
                e.traceId(), e.idempotencyKey(), e.payload().requestedAt(), e.payload().schemaVersion());
        meterRegistry.counter("api_read_cache_regen_publish_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", "success"
        ).increment();
        metricRecorder.recordCacheRegeneration(family.get(), reason.value(), "published");
    }

    @Override
    public void publishBatch(String targetType, List<Long> targetIds, CacheRegenerationReason reason, String endpoint) {
        if (targetIds == null || targetIds.isEmpty()) {
            return;
        }
        String cacheFamily = TARGET_TYPE_TO_FAMILY.getOrDefault(targetType, targetType.toLowerCase());
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.buildBatch(cacheFamily, targetType, targetIds, reason);
        log.info("[CacheRegen] batch eventType={} eventId={} targetType={} count={} reason={} endpoint={} traceId={} idempotencyKey={}",
                e.eventType(), e.eventId(), targetType, targetIds.size(), reason.value(), endpoint,
                e.traceId(), e.idempotencyKey());
        meterRegistry.counter("api_read_cache_regen_publish_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", "success"
        ).increment();
        metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "published");
    }

    @Override
    public void publishTarget(String targetType, CacheRegenerationReason reason, String endpoint) {
        String cacheFamily = TARGET_TYPE_TO_FAMILY.getOrDefault(targetType, targetType.toLowerCase());
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.buildTarget(cacheFamily, targetType, reason);
        log.info("[CacheRegen] target eventType={} eventId={} targetType={} reason={} endpoint={} traceId={} idempotencyKey={}",
                e.eventType(), e.eventId(), targetType, reason.value(), endpoint,
                e.traceId(), e.idempotencyKey());
        meterRegistry.counter("api_read_cache_regen_publish_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", "success"
        ).increment();
        metricRecorder.recordCacheRegeneration(cacheFamily, reason.value(), "published");
    }
}
