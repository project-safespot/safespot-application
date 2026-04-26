package com.safespot.asyncworker.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class WorkerMetrics {

    private final MeterRegistry meterRegistry;

    // Prometheus: worker_processed_total{event_type, result, queue_name}
    public void incrementProcessed(String eventType, String result, String queueName) {
        Counter.builder("worker.processed")
            .tag("event_type", eventType)
            .tag("result", result)
            .tag("queue_name", queueName)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_success_total{event_type, queue_name}
    public void incrementSuccess(String eventType, String queueName) {
        Counter.builder("worker.success")
            .tag("event_type", eventType)
            .tag("queue_name", queueName)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_failures_total{event_type, queue_name, reason}
    public void incrementFailures(String eventType, String queueName, String reason) {
        Counter.builder("worker.failures")
            .tag("event_type", eventType)
            .tag("queue_name", queueName)
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_processing_duration_seconds{event_type, queue_name}
    public void recordProcessingDuration(String eventType, String queueName, long durationMs) {
        Timer.builder("worker.processing.duration")
            .tag("event_type", eventType)
            .tag("queue_name", queueName)
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // Prometheus: worker_idempotency_skipped_total{event_type, queue_name}
    public void incrementIdempotencySkipped(String eventType, String queueName) {
        Counter.builder("worker.idempotency.skipped")
            .tag("event_type", eventType)
            .tag("queue_name", queueName)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_redis_write_total{event_type, operation, result}
    public void incrementRedisWrite(String eventType, String operation, String result) {
        Counter.builder("worker.redis.write")
            .tag("event_type", eventType)
            .tag("operation", operation)
            .tag("result", result)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_batch_size{queue_name}
    public void recordBatchSize(int size, String queueName) {
        DistributionSummary.builder("worker.batch.size")
            .tag("queue_name", queueName)
            .register(meterRegistry)
            .record(size);
    }

    // Prometheus: worker_partial_batch_failure_total{queue_name, event_type}
    public void incrementPartialBatchFailure(String queueName, String eventType) {
        Counter.builder("worker.partial.batch.failure")
            .tag("queue_name", queueName)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: worker_dlq_publish_total{event_type, reason}
    // app retry limit 초과 시점 — SQS DLQ 이동 직전 근사치
    public void incrementDlqPublish(String eventType, String reason) {
        Counter.builder("worker.dlq.publish")
            .tag("event_type", eventType)
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: cache_regeneration_requested_total{cache_key_family, event_type, reason, schema_version}
    public void incrementCacheRegenerationRequested(String cacheKeyFamily, String eventType, String reason, String schemaVersion) {
        Counter.builder("cache.regeneration.requested")
            .tag("cache_key_family", cacheKeyFamily != null ? cacheKeyFamily : "unknown")
            .tag("event_type", eventType)
            .tag("reason", reason != null ? reason : "unknown")
            .tag("schema_version", schemaVersion != null ? schemaVersion : "unknown")
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: cache_regeneration_completed_total{cache_key_family}
    public void incrementCacheRegenerationCompleted(String cacheKeyFamily) {
        Counter.builder("cache.regeneration.completed")
            .tag("cache_key_family", cacheKeyFamily != null ? cacheKeyFamily : "unknown")
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: cache_regeneration_failed_total{cache_key_family, reason}
    public void incrementCacheRegenerationFailed(String cacheKeyFamily, String reason) {
        Counter.builder("cache.regeneration.failed")
            .tag("cache_key_family", cacheKeyFamily != null ? cacheKeyFamily : "unknown")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    // Prometheus: redis_payload_size_bytes{cache_key_family}
    public void recordRedisPayloadSize(String cacheKeyFamily, long sizeBytes) {
        DistributionSummary.builder("redis.payload.size.bytes")
            .tag("cache_key_family", cacheKeyFamily)
            .register(meterRegistry)
            .record(sizeBytes);
    }
}
