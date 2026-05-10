package com.safespot.prescalingcontroller.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory state for the pre-scaling controller.
 *
 * On restart the ReconcileLoop re-derives this state from the current HPA minReplicas
 * and the DB disaster condition (§10 item 5).
 */
@Component
@Getter
public class PreScalingStateHolder {

    private final AtomicBoolean disasterActive = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastTriggerTime = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastRecoverTime = new AtomicReference<>(null);
    private final AtomicReference<Integer> currentMinReplicas = new AtomicReference<>(null);

    public void markDisasterActive(int minReplicas) {
        disasterActive.set(true);
        lastTriggerTime.set(Instant.now());
        currentMinReplicas.set(minReplicas);
    }

    public void markRecovered(int minReplicas) {
        disasterActive.set(false);
        lastRecoverTime.set(Instant.now());
        currentMinReplicas.set(minReplicas);
    }

    public void setCurrentMinReplicas(int minReplicas) {
        currentMinReplicas.set(minReplicas);
    }

    /**
     * Called on startup when the HPA is already at disasterMinReplicas
     * but the trigger time is unknown (controller restarted).
     * Sets lastTriggerTime to now so a full cooldown is required before recovery.
     */
    public void restoreDisasterStateFromHpa(int disasterMinReplicas) {
        disasterActive.set(true);
        lastTriggerTime.compareAndSet(null, Instant.now());
        currentMinReplicas.set(disasterMinReplicas);
    }

    public boolean isCooldownElapsed(long cooldownSeconds) {
        Instant trigger = lastTriggerTime.get();
        if (trigger == null) {
            return true;
        }
        return Instant.now().isAfter(trigger.plusSeconds(cooldownSeconds));
    }
}
