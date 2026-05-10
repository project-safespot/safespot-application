package com.safespot.prescalingcontroller.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the pre-scaling controller (§9).
 *
 * Exposed metrics:
 *   prescaling_active                   - 1 if disaster pre-scaling is active, 0 otherwise
 *   prescaling_target_min_replicas      - current patched minReplicas value
 *   prescaling_last_trigger_timestamp   - epoch seconds of last trigger
 *   prescaling_last_recover_timestamp   - epoch seconds of last recovery
 *   prescaling_k8s_patch_total          - total HPA patch attempts (success)
 *   prescaling_k8s_patch_failed_total   - total HPA patch failures
 *   prescaling_decision_total           - total reconcile decisions made
 */
@Component
@RequiredArgsConstructor
public class PreScalingMetrics {

    private final MeterRegistry registry;

    private final AtomicInteger activeGauge = new AtomicInteger(0);
    private final AtomicInteger targetMinReplicasGauge = new AtomicInteger(0);
    private final AtomicLong lastTriggerTimestamp = new AtomicLong(0);
    private final AtomicLong lastRecoverTimestamp = new AtomicLong(0);

    private Counter patchSuccessCounter;
    private Counter patchFailedCounter;
    private Counter decisionCounter;

    @PostConstruct
    public void init() {
        Gauge.builder("prescaling_active", activeGauge, AtomicInteger::get)
                .description("1 if disaster pre-scaling is currently active")
                .register(registry);

        Gauge.builder("prescaling_target_min_replicas", targetMinReplicasGauge, AtomicInteger::get)
                .description("Current minReplicas patched to HPA/api-public-read-surge")
                .register(registry);

        Gauge.builder("prescaling_last_trigger_timestamp", lastTriggerTimestamp, AtomicLong::get)
                .description("Epoch seconds of the last pre-scaling trigger")
                .register(registry);

        Gauge.builder("prescaling_last_recover_timestamp", lastRecoverTimestamp, AtomicLong::get)
                .description("Epoch seconds of the last pre-scaling recovery")
                .register(registry);

        patchSuccessCounter = Counter.builder("prescaling_k8s_patch_total")
                .description("Total successful HPA minReplicas patch calls")
                .register(registry);

        patchFailedCounter = Counter.builder("prescaling_k8s_patch_failed_total")
                .description("Total failed HPA minReplicas patch calls")
                .register(registry);

        decisionCounter = Counter.builder("prescaling_decision_total")
                .description("Total reconcile loop decisions (trigger or recover)")
                .register(registry);
    }

    public void setActive(boolean active, int minReplicas) {
        activeGauge.set(active ? 1 : 0);
        targetMinReplicasGauge.set(minReplicas);
    }

    public void recordTriggerTime(long epochSeconds) {
        lastTriggerTimestamp.set(epochSeconds);
    }

    public void recordRecoverTime(long epochSeconds) {
        lastRecoverTimestamp.set(epochSeconds);
    }

    public void recordPatchSuccess(int targetMinReplicas) {
        patchSuccessCounter.increment();
        targetMinReplicasGauge.set(targetMinReplicas);
    }

    public void recordPatchFailure() {
        patchFailedCounter.increment();
    }

    public void recordDecision() {
        decisionCounter.increment();
    }
}
