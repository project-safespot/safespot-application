package com.safespot.apicore.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
public class ApiCoreMetrics {

    private final MeterRegistry registry;

    private final AtomicLong shelterFullCount = new AtomicLong(0);
    private final AtomicLong shelterCrowdedCount = new AtomicLong(0);
    private final AtomicLong shelterOpenCount = new AtomicLong(0);

    @PostConstruct
    void init() {
        Gauge.builder("api_core_shelter_full_count", shelterFullCount, AtomicLong::get)
                .tag("service", "api-core")
                .register(registry);
        Gauge.builder("api_core_shelter_crowded_count", shelterCrowdedCount, AtomicLong::get)
                .tag("service", "api-core")
                .register(registry);
        Gauge.builder("api_core_shelter_open_count", shelterOpenCount, AtomicLong::get)
                .tag("service", "api-core")
                .register(registry);
    }

    public void incAdminApiCall(String method, String endpoint, String status) {
        Counter.builder("api_core_admin_api_calls_total")
                .tag("service", "api-core")
                .tag("method", method)
                .tag("endpoint", endpoint)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    public void incAdminAction(String action) {
        Counter.builder("api_core_admin_action_total")
                .tag("service", "api-core")
                .tag("action", action)
                .register(registry)
                .increment();
    }

    public void incAdminActionFailed(String action) {
        Counter.builder("api_core_admin_action_failed_total")
                .tag("service", "api-core")
                .tag("action", action)
                .register(registry)
                .increment();
    }

    public void incAuthLogin(String result, String reason) {
        Counter.builder("api_core_auth_login_total")
                .tag("service", "api-core")
                .tag("result", result)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void incCheckin() {
        Counter.builder("api_core_shelter_checkin_total")
                .tag("service", "api-core")
                .register(registry)
                .increment();
    }

    public void incCheckout() {
        Counter.builder("api_core_shelter_checkout_total")
                .tag("service", "api-core")
                .register(registry)
                .increment();
    }

    public void incSqsPublish(String eventType, String result, String queueName) {
        Counter.builder("api_core_sqs_publish_total")
                .tag("service", "api-core")
                .tag("event_type", eventType)
                .tag("result", result)
                .tag("queue_name", queueName)
                .register(registry)
                .increment();
    }

    public void incSqsPublishRetry(String eventType, String queueName) {
        Counter.builder("api_core_sqs_publish_retry_total")
                .tag("service", "api-core")
                .tag("event_type", eventType)
                .tag("queue_name", queueName)
                .register(registry)
                .increment();
    }

    public void updateShelterCounts(long full, long crowded, long open) {
        shelterFullCount.set(full);
        shelterCrowdedCount.set(crowded);
        shelterOpenCount.set(open);
    }
}
