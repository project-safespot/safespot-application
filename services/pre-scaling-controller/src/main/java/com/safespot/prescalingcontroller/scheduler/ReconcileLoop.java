package com.safespot.prescalingcontroller.scheduler;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import com.safespot.prescalingcontroller.repository.DisasterAlertPollingRepository;
import com.safespot.prescalingcontroller.repository.DisasterConditionResult;
import com.safespot.prescalingcontroller.service.GenericHpaPatchService;
import com.safespot.prescalingcontroller.service.HpaSnapshot;
import com.safespot.prescalingcontroller.service.IngressPatchService;
import com.safespot.prescalingcontroller.service.PreScalingStateHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileLoop {

    private final DisasterAlertPollingRepository alertRepository;
    private final GenericHpaPatchService hpaPatchService;
    private final IngressPatchService ingressPatchService;
    private final PreScalingStateHolder stateHolder;
    private final PreScalingMetrics metrics;
    private final PreScalingProperties properties;

    @PostConstruct
    public void initializeState() {
        if (!properties.isEnabled()) {
            log.info("[PreScaling] disabled, skipping initialization");
            return;
        }

        Optional<HpaSnapshot> surgeSnapshotOpt = hpaPatchService.getHpaSnapshot(properties.getSurge().getTargetHpaName());
        Optional<HpaSnapshot> baseSnapshotOpt = properties.getBase().isEnabled()
                ? hpaPatchService.getHpaSnapshot(properties.getBase().getTargetHpaName())
                : Optional.empty();

        if (surgeSnapshotOpt.isEmpty()) {
            log.warn("[PreScaling] cannot read surge HPA on startup");
            return;
        }

        HpaSnapshot surgeSnapshot = surgeSnapshotOpt.get();
        stateHolder.setCurrentMinReplicas(surgeSnapshot.minReplicas());

        if (properties.getBase().isEnabled()
                && baseSnapshotOpt.isPresent()
                && baseSnapshotOpt.get().maxReplicas() == properties.getBase().getDisasterMaxReplicas()) {
            if (surgeSnapshot.minReplicas() >= properties.getSurge().getPeakMinReplicas()) {
                stateHolder.restorePeakStateFromCurrentState(surgeSnapshot.minReplicas());
                metrics.setActive(true, surgeSnapshot.minReplicas());
                log.info("[PreScaling] startup state inferred as DISASTER_PEAK");
                return;
            }

            if (surgeSnapshot.minReplicas() >= properties.getSurge().getSustainedMinReplicas()) {
                stateHolder.markDisasterSustained(surgeSnapshot.minReplicas());
                metrics.setActive(true, surgeSnapshot.minReplicas());
                log.info("[PreScaling] startup state inferred as DISASTER_SUSTAINED");
                return;
            }
        }

        stateHolder.markNormal(properties.getSurge().getNormalMinReplicas());
        metrics.setActive(false, properties.getSurge().getNormalMinReplicas());
        log.info("[PreScaling] startup state inferred as NORMAL");
    }

    @Scheduled(fixedDelayString = "${pre-scaling.polling-interval-ms:30000}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }

        DisasterConditionResult condition = safeQueryCondition();
        Optional<HpaSnapshot> surgeSnapshotOpt = hpaPatchService.getHpaSnapshot(properties.getSurge().getTargetHpaName());
        if (surgeSnapshotOpt.isEmpty()) {
            log.warn("[PreScaling] surge HPA not found, skipping reconcile");
            return;
        }

        HpaSnapshot surgeSnapshot = surgeSnapshotOpt.get();
        HpaSnapshot baseSnapshot = null;
        if (properties.getBase().isEnabled()) {
            Optional<HpaSnapshot> baseSnapshotOpt = hpaPatchService.getHpaSnapshot(properties.getBase().getTargetHpaName());
            if (baseSnapshotOpt.isEmpty()) {
                log.warn("[PreScaling] base HPA not found, skipping reconcile");
                return;
            }
            baseSnapshot = baseSnapshotOpt.get();
        }

        PreScalingStateHolder.State currentState = stateHolder.getState().get();
        log.debug("[PreScaling] reconcile: condition={}, state={}, surgeMin={}, baseMax={}",
                condition, currentState, surgeSnapshot.minReplicas(),
                baseSnapshot != null ? baseSnapshot.maxReplicas() : null);

        if (condition == DisasterConditionResult.UNKNOWN) {
            log.warn("[PreScaling] condition UNKNOWN, holding current state={}", currentState);
            return;
        }

        switch (currentState) {
            case NORMAL -> handleNormal(condition, surgeSnapshot, baseSnapshot);
            case DISASTER_PEAK -> handlePeak(condition, surgeSnapshot, baseSnapshot);
            case DISASTER_SUSTAINED -> handleSustained(condition, surgeSnapshot, baseSnapshot);
        }
    }

    private void handleNormal(DisasterConditionResult condition, HpaSnapshot surgeSnapshot, HpaSnapshot baseSnapshot) {
        if (condition == DisasterConditionResult.ACTIVE && ensurePeakState()) {
            stateHolder.markDisasterPeak(properties.getSurge().getPeakMinReplicas());
            metrics.recordDecision();
            metrics.setActive(true, properties.getSurge().getPeakMinReplicas());
            metrics.recordTriggerTime(Instant.now().getEpochSecond());
            return;
        }

        stateHolder.setCurrentMinReplicas(surgeSnapshot.minReplicas());
        metrics.setActive(false, surgeSnapshot.minReplicas());
    }

    private void handlePeak(DisasterConditionResult condition, HpaSnapshot surgeSnapshot, HpaSnapshot baseSnapshot) {
        if (stateHolder.isPeakWindowElapsed(properties.getSurge().getPeakWindowSeconds())) {
            if (ensureSustainedState()) {
                stateHolder.markDisasterSustained(properties.getSurge().getSustainedMinReplicas());
                metrics.recordDecision();
                metrics.setActive(true, properties.getSurge().getSustainedMinReplicas());
            }
            return;
        }

        if (condition == DisasterConditionResult.ACTIVE || condition == DisasterConditionResult.INACTIVE) {
            ensurePeakState();
        }
        stateHolder.setCurrentMinReplicas(surgeSnapshot.minReplicas());
        metrics.setActive(true, properties.getSurge().getPeakMinReplicas());
    }

    private void handleSustained(DisasterConditionResult condition, HpaSnapshot surgeSnapshot, HpaSnapshot baseSnapshot) {
        if (condition == DisasterConditionResult.ACTIVE) {
            if (ensurePeakState()) {
                stateHolder.markDisasterPeak(properties.getSurge().getPeakMinReplicas());
                metrics.recordDecision();
                metrics.setActive(true, properties.getSurge().getPeakMinReplicas());
                metrics.recordTriggerTime(Instant.now().getEpochSecond());
            }
            return;
        }

        ensureSustainedState();
        stateHolder.setCurrentMinReplicas(surgeSnapshot.minReplicas());
        metrics.setActive(true, properties.getSurge().getSustainedMinReplicas());
    }

    private boolean ensurePeakState() {
        boolean surgePatched = hpaPatchService.patchMinReplicas(
                properties.getSurge().getTargetHpaName(),
                properties.getSurge().getPeakMinReplicas()
        );
        if (!surgePatched) {
            return false;
        }

        boolean routingPatched = !properties.getRouting().isEnabled() || ingressPatchService.patchWeights(
                properties.getRouting().getDisasterBaseWeight(),
                properties.getRouting().getDisasterSurgeWeight()
        );
        if (!routingPatched) {
            return false;
        }

        return !properties.getBase().isEnabled() || !properties.getBase().isRestrictAfterRouting() || hpaPatchService.patchMinMaxReplicas(
                properties.getBase().getTargetHpaName(),
                properties.getBase().getDisasterMinReplicas(),
                properties.getBase().getDisasterMaxReplicas()
        );
    }

    private boolean ensureSustainedState() {
        boolean surgePatched = hpaPatchService.patchMinReplicas(
                properties.getSurge().getTargetHpaName(),
                properties.getSurge().getSustainedMinReplicas()
        );
        if (!surgePatched) {
            return false;
        }

        boolean routingPatched = !properties.getRouting().isEnabled() || ingressPatchService.patchWeights(
                properties.getRouting().getDisasterBaseWeight(),
                properties.getRouting().getDisasterSurgeWeight()
        );
        if (!routingPatched) {
            return false;
        }

        return !properties.getBase().isEnabled() || !properties.getBase().isRestrictAfterRouting() || hpaPatchService.patchMinMaxReplicas(
                properties.getBase().getTargetHpaName(),
                properties.getBase().getDisasterMinReplicas(),
                properties.getBase().getDisasterMaxReplicas()
        );
    }

    private DisasterConditionResult safeQueryCondition() {
        try {
            return alertRepository.queryCondition();
        } catch (Exception e) {
            log.error("[PreScaling] DB polling failed, holding state as UNKNOWN", e);
            return DisasterConditionResult.UNKNOWN;
        }
    }
}
