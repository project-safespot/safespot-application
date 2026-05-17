package com.safespot.apipublicread.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class PublicReadMetricRecorder {

    private final MeterRegistry meterRegistry;

    @Value("${management.metrics.tags.service:api-public-read}")
    private String service;

    @Value("${management.metrics.tags.region:seoul}")
    private String region;

    public PublicReadMetricRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.service = "api-public-read";
        this.region = "seoul";
    }

    public void recordCacheRequest(String cache, String result) {
        meterRegistry.counter("safespot.cache.requests",
                "service", service,
                "cache", lowCardinality(cache),
                "region", region,
                "result", lowCardinality(result)
        ).increment();
    }

    public void recordCacheFallback(String cache, String reason) {
        meterRegistry.counter("safespot.cache.fallback",
                "service", service,
                "cache", lowCardinality(cache),
                "region", region,
                "reason", lowCardinality(reason)
        ).increment();
    }

    public void recordDbFallbackQuery(String cache, String repository, String reason, String result) {
        meterRegistry.counter("safespot.db.fallback.queries",
                "service", service,
                "cache", lowCardinality(cache),
                "repository", lowCardinality(repository),
                "region", region,
                "reason", lowCardinality(reason),
                "result", lowCardinality(result)
        ).increment();
    }

    public void recordDbFallbackLatency(String cache, String repository, String result, long durationMs) {
        Timer.builder("safespot.db.fallback")
                .tag("service", service)
                .tag("cache", lowCardinality(cache))
                .tag("repository", lowCardinality(repository))
                .tag("region", region)
                .tag("result", lowCardinality(result))
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordFallbackSingleflight(String cache, String scope, String result) {
        meterRegistry.counter("safespot.fallback.singleflight",
                "service", service,
                "cache", lowCardinality(cache),
                "region", region,
                "scope", lowCardinality(scope),
                "result", lowCardinality(result)
        ).increment();
    }

    public void recordCacheRegeneration(String cache, String reason, String result) {
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", service,
                "cache", lowCardinality(cache),
                "region", region,
                "reason", lowCardinality(reason),
                "result", lowCardinality(result)
        ).increment();
    }

    public String region() {
        return region;
    }

    private static String lowCardinality(String value) {
        return Optional.ofNullable(value)
                .filter(v -> v.matches("[a-zA-Z0-9_./:{}-]+"))
                .orElse("unknown");
    }
}
