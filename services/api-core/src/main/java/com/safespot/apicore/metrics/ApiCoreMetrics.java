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

    @PostConstruct
    void init() {
        Gauge.builder("api_core_shelter_full_count", shelterFullCount, AtomicLong::get)
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

    public void updateShelterFullCount(long count) {
        shelterFullCount.set(count);
    }
}
