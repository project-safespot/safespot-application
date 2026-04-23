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
    // result: success | failure | skipped
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
    // 모든 attempt(success/failure/skipped) 기준으로 기록
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
    // event_type: MDC에서 SqsBatchProcessor가 주입 (unknown = 컨텍스트 없음)
    // operation: SET | DEL
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
    // 메시지 단위 기준: process() 에서 실패한 event_type별로 각각 호출
    public void incrementPartialBatchFailure(String queueName, String eventType) {
        Counter.builder("worker.partial.batch.failure")
            .tag("queue_name", queueName)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }
}
