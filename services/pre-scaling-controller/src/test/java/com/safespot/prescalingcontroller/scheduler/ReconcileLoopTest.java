package com.safespot.prescalingcontroller.scheduler;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import com.safespot.prescalingcontroller.repository.DisasterAlertPollingRepository;
import com.safespot.prescalingcontroller.repository.DisasterConditionResult;
import com.safespot.prescalingcontroller.service.HpaPatchService;
import com.safespot.prescalingcontroller.service.HpaSnapshot;
import com.safespot.prescalingcontroller.service.PreScalingStateHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconcileLoopTest {

    @Mock private DisasterAlertPollingRepository alertRepository;
    @Mock private HpaPatchService hpaPatchService;
    @Mock private PreScalingMetrics metrics;

    private PreScalingStateHolder stateHolder;
    private PreScalingProperties properties;
    private ReconcileLoop loop;

    @BeforeEach
    void setUp() {
        stateHolder = new PreScalingStateHolder();
        properties = buildProperties();
        loop = new ReconcileLoop(alertRepository, hpaPatchService, stateHolder, metrics, properties);
    }

    // ── ACTIVE trigger ────────────────────────────────────────────────────────

    @Test
    void reconcile_active_patchesToDisasterMin() {
        // normalMinReplicas=1, so HPA starts at 1 in normal state
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(1, 10)));
        when(hpaPatchService.patchMinReplicas(1, 3, 10)).thenReturn(true);

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas(1, 3, 10);
        verify(metrics).recordDecision();
    }

    @Test
    void reconcile_alreadyActive_doesNotRepatch() {
        stateHolder.markDisasterActive(3);

        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcile_hpaDriftedBelowDisasterMin_repatches() {
        stateHolder.markDisasterActive(3);

        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(1, 10)));

        loop.reconcile();

        verify(hpaPatchService).patchMinReplicas(1, 3, 10);
    }

    // ── INACTIVE recovery ─────────────────────────────────────────────────────

    @Test
    void reconcile_inactive_cooldownNotElapsed_doesNotRecover() {
        stateHolder.markDisasterActive(3);

        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));

        loop.reconcile(); // triggered just now; 1800s cooldown not elapsed

        verify(hpaPatchService, never()).patchMinReplicas(3, 1, 10);
    }

    @Test
    void reconcile_inactive_cooldownElapsed_recovers() {
        stateHolder.markDisasterActive(3);
        stateHolder.getLastTriggerTime().set(Instant.now().minusSeconds(1801));

        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));
        when(hpaPatchService.patchMinReplicas(3, 1, 10)).thenReturn(true);

        loop.reconcile();

        // recovers to normalMinReplicas=1, not 0
        verify(hpaPatchService).patchMinReplicas(3, 1, 10);
        verify(metrics).recordDecision();
    }

    @Test
    void reconcile_noDisasterAndNormalState_noAction() {
        // HPA already at normalMinReplicas=1, stateHolder not active
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(1, 10)));

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    // ── UNKNOWN — DB failure must not trigger recovery ─────────────────────────

    @Test
    void reconcile_unknown_doesNotPatch() {
        stateHolder.markDisasterActive(3);

        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.UNKNOWN);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));

        loop.reconcile();

        // DB failure while active must NOT trigger recovery
        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcile_dbException_treatedAsUnknown_doesNotPatch() {
        stateHolder.markDisasterActive(3);

        when(alertRepository.queryCondition()).thenThrow(new RuntimeException("DB down"));
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    @Test
    void reconcile_unknown_inNormalState_doesNotTrigger() {
        // stateHolder: disasterActive=false (default)
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.UNKNOWN);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(1, 10)));

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    // ── Disabled controller ───────────────────────────────────────────────────

    @Test
    void reconcile_controllerDisabled_doesNothing() {
        properties.setEnabled(false);

        loop.reconcile();

        verifyNoInteractions(alertRepository, hpaPatchService, metrics);
    }

    // ── HPA not found ─────────────────────────────────────────────────────────

    @Test
    void reconcile_hpaNotFound_skipsReconcile() {
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.empty());

        loop.reconcile();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    // ── Startup initialization ────────────────────────────────────────────────

    @Test
    void initializeState_activeAndHpaBelowDisasterMin_patches() {
        // Startup: HPA is at normalMinReplicas=1, DB says disaster ACTIVE → patch to 3
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(1, 10)));
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.ACTIVE);
        when(hpaPatchService.patchMinReplicas(1, 3, 10)).thenReturn(true);

        loop.initializeState();

        verify(hpaPatchService).patchMinReplicas(1, 3, 10);
    }

    @Test
    void initializeState_inactiveHpaAtDisasterMin_entersCooldown() {
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.INACTIVE);

        loop.initializeState();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
        assert stateHolder.getDisasterActive().get();
    }

    @Test
    void initializeState_unknown_holdsCurrentState() {
        when(hpaPatchService.getHpaSnapshot()).thenReturn(Optional.of(new HpaSnapshot(3, 10)));
        when(alertRepository.queryCondition()).thenReturn(DisasterConditionResult.UNKNOWN);

        loop.initializeState();

        verify(hpaPatchService, never()).patchMinReplicas(anyInt(), anyInt(), anyInt());
    }

    @Test
    void initializeState_disabled_skips() {
        properties.setEnabled(false);

        loop.initializeState();

        verifyNoInteractions(hpaPatchService, alertRepository);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private PreScalingProperties buildProperties() {
        PreScalingProperties props = new PreScalingProperties();
        props.setEnabled(true);
        props.setNamespace("application");
        props.setTargetHpaName("api-public-read-surge");

        PreScalingProperties.Trigger trigger = new PreScalingProperties.Trigger();
        trigger.setDisasterTypes(List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE"));
        trigger.setMinLevelRank(3);
        trigger.setLookbackMinutes(10);
        props.setTrigger(trigger);

        PreScalingProperties.Surge surge = new PreScalingProperties.Surge();
        surge.setNormalMinReplicas(1);  // EKS rejects minReplicas:0; use 1 as normal floor
        surge.setDisasterMinReplicas(3);
        surge.setMaxReplicas(10);
        surge.setCooldownSeconds(1800);
        props.setSurge(surge);

        return props;
    }
}
