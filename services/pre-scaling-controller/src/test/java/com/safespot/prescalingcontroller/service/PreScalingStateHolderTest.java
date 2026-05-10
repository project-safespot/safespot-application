package com.safespot.prescalingcontroller.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreScalingStateHolderTest {

    private PreScalingStateHolder holder;

    @BeforeEach
    void setUp() {
        holder = new PreScalingStateHolder();
    }

    @Test
    void initialState_isNotDisasterActive() {
        assertThat(holder.getDisasterActive().get()).isFalse();
        assertThat(holder.getLastTriggerTime().get()).isNull();
        assertThat(holder.getLastRecoverTime().get()).isNull();
    }

    @Test
    void markDisasterActive_setsState() {
        holder.markDisasterActive(3);

        assertThat(holder.getDisasterActive().get()).isTrue();
        assertThat(holder.getLastTriggerTime().get()).isNotNull();
        assertThat(holder.getCurrentMinReplicas().get()).isEqualTo(3);
    }

    @Test
    void markRecovered_clearsDisasterState() {
        holder.markDisasterActive(3);
        holder.markRecovered(0);

        assertThat(holder.getDisasterActive().get()).isFalse();
        assertThat(holder.getLastRecoverTime().get()).isNotNull();
        assertThat(holder.getCurrentMinReplicas().get()).isEqualTo(0);
    }

    @Test
    void isCooldownElapsed_withZeroSeconds_returnsTrue() {
        holder.markDisasterActive(3);
        assertThat(holder.isCooldownElapsed(0)).isTrue();
    }

    @Test
    void isCooldownElapsed_withLargeCooldown_returnsFalse() {
        holder.markDisasterActive(3);
        assertThat(holder.isCooldownElapsed(1_800)).isFalse();
    }

    @Test
    void isCooldownElapsed_withNoTriggerTime_returnsTrue() {
        // No trigger has been set — cooldown considered elapsed
        assertThat(holder.isCooldownElapsed(1_800)).isTrue();
    }

    @Test
    void restoreDisasterStateFromHpa_setsActiveAndDoesNotOverrideTriggerTime() {
        holder.restoreDisasterStateFromHpa(3);

        assertThat(holder.getDisasterActive().get()).isTrue();
        assertThat(holder.getLastTriggerTime().get()).isNotNull();

        // Calling again should NOT reset lastTriggerTime (compareAndSet)
        var firstTriggerTime = holder.getLastTriggerTime().get();
        holder.restoreDisasterStateFromHpa(3);
        assertThat(holder.getLastTriggerTime().get()).isEqualTo(firstTriggerTime);
    }
}
