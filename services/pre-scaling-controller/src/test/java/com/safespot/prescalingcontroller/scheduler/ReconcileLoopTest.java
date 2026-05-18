package com.safespot.prescalingcontroller.scheduler;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import com.safespot.prescalingcontroller.repository.DisasterAlertPollingRepository;
import com.safespot.prescalingcontroller.repository.DisasterConditionResult;
import com.safespot.prescalingcontroller.service.GenericHpaPatchService;
import com.safespot.prescalingcontroller.service.HpaSnapshot;
import com.safespot.prescalingcontroller.service.IngressPatchService;
import com.safespot.prescalingcontroller.service.PreScalingStateHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconcileLoopTest {

    @Mock private DisasterAlertPollingRepository alertRepository;
    @Mock private GenericHpaPatchService hpaPatchService;
    @Mock private IngressPatchService ingressPatchService;
    @Mock private PreScalingMetrics metrics;

    private PreScalingStateHolder stateHolder;
    private ReconcileLoop loop;
    private PreScalingProperties properties;

    @BeforeEach
    void setUp() {
        stateHolder = new PreScalingStateHolder();
        properties = buildProperties();
        loop = new ReconcileLoop(alertRepository, hpaPatchService, ingressPatchService, stateHolder, metrics, properties);
    }

    @Test
    void normalAndActive_entersPeakInOrder() {
        stubSnapshots(1, 20, 1, 5);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 8)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(true);
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 1)).thenReturn(true);

        loop.reconcile();

        InOrder inOrder = inOrder(hpaPatchService, ingressPatchService);
        inOrder.verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 8);
        inOrder.verify(ingressPatchService).patchWeights(0, 100);
        inOrder.verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_PEAK);
    }

    @Test
    void normalAndActive_routingFailure_doesNotRestrictBaseOrAdvanceState() {
        stubSnapshots(1, 20, 1, 5);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 8)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(false);

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.NORMAL);
    }

    @Test
    void peakWithoutElapsedWindow_keepsPeakState() {
        stateHolder.markDisasterPeak(8);
        stubSnapshots(8, 20, 1, 1);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 8)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(true);
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 1)).thenReturn(true);

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 8);
        verify(ingressPatchService).patchWeights(0, 100);
        verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_PEAK);
    }

    @Test
    void peakWithElapsedWindow_transitionsToSustained() {
        stateHolder.markDisasterPeak(8);
        stateHolder.getLastPeakStartTime().set(Instant.now().minusSeconds(1801));
        stubSnapshots(8, 20, 1, 1);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 3)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(true);
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 1)).thenReturn(true);

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 3);
        verify(ingressPatchService).patchWeights(0, 100);
        verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_SUSTAINED);
    }

    @Test
    void sustainedAndInactive_keepsSustainedState() {
        stateHolder.markDisasterSustained(3);
        stubSnapshots(3, 20, 1, 1);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 3)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(true);
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 1)).thenReturn(true);

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 3);
        verify(ingressPatchService).patchWeights(0, 100);
        verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_SUSTAINED);
    }

    @Test
    void sustainedAndActive_reentersPeak() {
        stateHolder.markDisasterSustained(3);
        stubSnapshots(3, 20, 1, 1);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 8)).thenReturn(true);
        when(ingressPatchService.patchWeights(0, 100)).thenReturn(true);
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 1)).thenReturn(true);

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 8);
        verify(ingressPatchService).patchWeights(0, 100);
        verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.DISASTER_PEAK);
    }

    @Test
    void unknown_doesNotRestoreOrPatch() {
        stateHolder.markDisasterSustained(3);
        stubSnapshots(3, 20, 1, 1);
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.UNKNOWN);

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas("api-public-read-surge", 1);
        verify(ingressPatchService, never()).patchWeights(100, 0);
        verify(hpaPatchService, never()).patchMinMaxReplicas("api-public-read", 1, 5);
    }

    private void stubSnapshots(int surgeMin, int surgeMax, int baseMin, int baseMax) {
        when(hpaPatchService.getHpaSnapshot("api-public-read-surge"))
                .thenReturn(Optional.of(new HpaSnapshot(surgeMin, surgeMax)));
        when(hpaPatchService.getHpaSnapshot("api-public-read"))
                .thenReturn(Optional.of(new HpaSnapshot(baseMin, baseMax)));
    }

    private PreScalingProperties buildProperties() {
        PreScalingProperties properties = new PreScalingProperties();
        properties.setEnabled(true);
        properties.setNamespace("application");
        return properties;
    }
}
