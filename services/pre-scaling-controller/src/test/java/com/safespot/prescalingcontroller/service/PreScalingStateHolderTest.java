package com.safespot.prescalingcontroller.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PreScalingStateHolderTest {

    private PreScalingStateHolder holder;

    @BeforeEach
    void setUp() {
        holder = new PreScalingStateHolder();
    }

    @Test
    void initialState_isNormal() {
        assertThat(holder.getState().get()).isEqualTo(PreScalingStateHolder.State.NORMAL);
        assertThat(holder.getLastPeakStartTime().get()).isNull();
    }

    @Test
    void markDisasterPeak_setsPeakStateAndTimestamp() {
        holder.markDisasterPeak(8);

        assertThat(holder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_PEAK);
        assertThat(holder.getLastPeakStartTime().get()).isNotNull();
        assertThat(holder.getCurrentMinReplicas().get()).isEqualTo(8);
    }

    @Test
    void markDisasterSustained_setsSustainedState() {
        holder.markDisasterSustained(3);

        assertThat(holder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_SUSTAINED);
        assertThat(holder.getCurrentMinReplicas().get()).isEqualTo(3);
    }

    @Test
    void markNormal_setsNormalState() {
        holder.markDisasterPeak(8);
        holder.markNormal(1);

        assertThat(holder.getState().get()).isEqualTo(PreScalingStateHolder.State.NORMAL);
        assertThat(holder.getCurrentMinReplicas().get()).isEqualTo(1);
    }

    @Test
    void isPeakWindowElapsed_zeroSeconds_returnsTrue() {
        holder.markDisasterPeak(8);
        assertThat(holder.isPeakWindowElapsed(0)).isTrue();
    }

    @Test
    void isPeakWindowElapsed_beforeDeadline_returnsFalse() {
        holder.markDisasterPeak(8);
        assertThat(holder.isPeakWindowElapsed(1_800)).isFalse();
    }

    @Test
    void isPeakWindowElapsed_withoutPeakTimestamp_returnsTrue() {
        assertThat(holder.isPeakWindowElapsed(1_800)).isTrue();
    }

    @Test
    void restorePeakStateFromCurrentState_preservesExistingTimestamp() {
        holder.restorePeakStateFromCurrentState(8);
        Instant first = holder.getLastPeakStartTime().get();

        holder.restorePeakStateFromCurrentState(8);

        assertThat(holder.getLastPeakStartTime().get()).isEqualTo(first);
    }
}
