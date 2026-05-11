package com.safespot.apipublicread.event;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "safespot.cache-regeneration.publisher-mode",
        havingValue = "log",
        matchIfMissing = true
)
@RequiredArgsConstructor
public class LogOnlyCacheRegenerationPublisher implements CacheRegenerationPublisher {

    private final CacheKeyFamilyResolver resolver;
    private final MeterRegistry meterRegistry;

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
    }
}
