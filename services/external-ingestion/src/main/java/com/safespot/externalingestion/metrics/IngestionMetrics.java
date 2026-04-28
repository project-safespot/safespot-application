package com.safespot.externalingestion.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class IngestionMetrics {

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, AtomicLong> lastSuccessTimestamps = new ConcurrentHashMap<>();

    @PostConstruct
    void initStalenessGauges() {
        for (String source : new String[]{
                "SAFETY_DATA_ALERT", "KMA_EARTHQUAKE", "SEOUL_EARTHQUAKE",
                "FORESTRY_LANDSLIDE", "SEOUL_RIVER_LEVEL", "KMA_WEATHER",
                "AIR_KOREA_AIR_QUALITY", "SEOUL_SHELTER_EARTHQUAKE",
                "SEOUL_SHELTER_LANDSLIDE", "SEOUL_SHELTER_FLOOD"}) {
            AtomicLong ts = new AtomicLong(0);
            lastSuccessTimestamps.put(source, ts);
            Gauge.builder("ingestion_last_success_timestamp", ts, AtomicLong::get)
                    .tag("source", source)
                    .register(registry);
        }
    }

    public void recordLastSuccess(String source) {
        lastSuccessTimestamps
                .computeIfAbsent(source, k -> {
                    AtomicLong ts = new AtomicLong(0);
                    Gauge.builder("ingestion_last_success_timestamp", ts, AtomicLong::get)
                            .tag("source", k)
                            .register(registry);
                    return ts;
                })
                .set(Instant.now().getEpochSecond());
    }

    public void incrementPollingIteration(String source) {
        Counter.builder("ingestion_polling_loop_iteration_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementSkipped(String source, String reason) {
        Counter.builder("ingestion_polling_loop_skipped_total")
            .tag("source", source)
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    public void incrementApiCall(String source) {
        Counter.builder("ingestion_external_api_call_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementApiFailure(String source, String type) {
        Counter.builder("ingestion_external_api_failure_total")
            .tag("source", source)
            .tag("type", type)
            .register(registry)
            .increment();
    }

    public void recordApiLatency(String source, long millis) {
        Timer.builder("ingestion_external_api_latency_seconds")
            .tag("source", source)
            .register(registry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    public void incrementRateLimitExceeded(String source) {
        Counter.builder("ingestion_external_api_rate_limit_exceeded_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementRetry(String source) {
        Counter.builder("ingestion_external_api_retry_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void recordFetchDuration(String source, long millis) {
        Timer.builder("ingestion_total_fetch_duration_seconds")
            .tag("source", source)
            .register(registry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    public void incrementDisasterAlertReceived(String source) {
        Counter.builder("ingestion_disaster_alert_received_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementRecordsFetched(String source, int count) {
        Counter.builder("ingestion_records_fetched_total")
            .tag("source", source)
            .register(registry)
            .increment(count);
    }

    public void recordNormalizationDuration(String source, long millis) {
        Timer.builder("ingestion_normalization_duration_seconds")
            .tag("source", source)
            .register(registry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    public void incrementNormalizationSuccess(String source) {
        Counter.builder("ingestion_records_normalized_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementNormalizationFailure(String source, String reason) {
        Counter.builder("ingestion_normalization_failure_total")
            .tag("source", source)
            .tag("reason", reason)
            .register(registry)
            .increment();
    }

    public void incrementRecordsFailed(String source) {
        Counter.builder("ingestion_records_failed_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementDuplicatePayload(String source) {
        Counter.builder("ingestion_duplicate_payload_total")
            .tag("source", source)
            .register(registry)
            .increment();
    }

    public void incrementSqsPublish(String source, String queueName, String eventType) {
        Counter.builder("ingestion_sqs_publish_total")
            .tag("source", source)
            .tag("queue_name", queueName)
            .tag("event_type", eventType)
            .register(registry)
            .increment();
    }

    public void incrementSqsPublishFailure(String source, String queueName, String eventType) {
        Counter.builder("ingestion_sqs_publish_failure_total")
            .tag("source", source)
            .tag("queue_name", queueName)
            .tag("event_type", eventType)
            .register(registry)
            .increment();
    }
}
