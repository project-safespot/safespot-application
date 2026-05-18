package com.safespot.prescalingcontroller.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    public enum State {
        NORMAL,
        DISASTER_PEAK,
        DISASTER_SUSTAINED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.NORMAL);
    private final AtomicReference<Instant> lastPeakStartTime = new AtomicReference<>(null);
    private final AtomicReference<Integer> currentMinReplicas = new AtomicReference<>(null);

    public void markDisasterPeak(int minReplicas) {
        state.set(State.DISASTER_PEAK);
        lastPeakStartTime.set(Instant.now());
        currentMinReplicas.set(minReplicas);
    }

    public void markDisasterSustained(int minReplicas) {
        state.set(State.DISASTER_SUSTAINED);
        currentMinReplicas.set(minReplicas);
    }

    public void markNormal(int minReplicas) {
        state.set(State.NORMAL);
        currentMinReplicas.set(minReplicas);
    }

    public void setCurrentMinReplicas(int minReplicas) {
        currentMinReplicas.set(minReplicas);
    }

    public void restorePeakStateFromCurrentState(int minReplicas) {
        state.set(State.DISASTER_PEAK);
        lastPeakStartTime.compareAndSet(null, Instant.now());
        currentMinReplicas.set(minReplicas);
    }

    public boolean isPeakWindowElapsed(long peakWindowSeconds) {
        Instant peakStart = lastPeakStartTime.get();
        if (peakStart == null) {
            return true;
        }
        return !Instant.now().isBefore(peakStart.plusSeconds(peakWindowSeconds));
    }
}
