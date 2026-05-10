package com.safespot.scenariosimulator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SimulatorMetrics {

    private final Counter requestsTotal;
    private final MeterRegistry registry;

    public SimulatorMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requestsTotal = Counter.builder("scenario_simulator_requests_total")
                .description("Total simulator API requests")
                .register(registry);
    }

    public void incRequests() {
        requestsTotal.increment();
    }

    public void incDisasterAlertsCreated(int count) {
        Counter.builder("scenario_simulator_disaster_alerts_created_total")
                .register(registry)
                .increment(count);
    }

    public void incResidentsCreated(int count) {
        Counter.builder("scenario_simulator_residents_created_total")
                .register(registry)
                .increment(count);
    }

    public void incCleanup() {
        Counter.builder("scenario_simulator_cleanup_total")
                .register(registry)
                .increment();
    }

    public void incEventsPublished(String eventType) {
        Counter.builder("scenario_simulator_events_published_total")
                .tag("event_type", eventType)
                .register(registry)
                .increment();
    }

    public void incFailure(String reason) {
        Counter.builder("scenario_simulator_failures_total")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
