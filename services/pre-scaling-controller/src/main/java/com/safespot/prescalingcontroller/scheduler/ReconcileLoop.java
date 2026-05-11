package com.safespot.prescalingcontroller.scheduler;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import com.safespot.prescalingcontroller.repository.DisasterAlertPollingRepository;
import com.safespot.prescalingcontroller.repository.DisasterConditionResult;
import com.safespot.prescalingcontroller.service.HpaPatchService;
import com.safespot.prescalingcontroller.service.HpaSnapshot;
import com.safespot.prescalingcontroller.service.PreScalingStateHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * DB polling reconcile loop for the pre-scaling controller.
 *
 * Reconcile state machine (§3):
 *   ACTIVE  → patch HPA to disasterMinReplicas if not already there
 *   INACTIVE → patch HPA to normalMinReplicas after cooldown elapses
 *   UNKNOWN  → hold current state; do NOT trigger recovery on DB failure
 *
 * This is NOT a SQS consumer. Deployment replicas are never directly controlled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileLoop {

    private final DisasterAlertPollingRepository alertRepository;
    private final HpaPatchService hpaPatchService;
    private final PreScalingStateHolder stateHolder;
    private final PreScalingMetrics metrics;
    private final PreScalingProperties properties;

    /**
     * On startup: re-derive state from current HPA snapshot + DB condition (§10 item 5).
     */
    @PostConstruct
    public void initializeState() {
        if (!properties.isEnabled()) {
            log.info("[PreScaling] disabled, skipping initialization");
            return;
        }

        log.info("[PreScaling] initializing state — trigger: types={}, minLevelRank={}, lookbackMinutes={}",
                properties.getTrigger().getDisasterTypes(),
                properties.getTrigger().getMinLevelRank(),
                properties.getTrigger().getLookbackMinutes());

        Optional<HpaSnapshot> snapshotOpt = hpaPatchService.getHpaSnapshot();
        if (snapshotOpt.isEmpty()) {
            log.warn("[PreScaling] cannot read HPA on startup — will reconcile on first tick");
            return;
        }

        HpaSnapshot snapshot = snapshotOpt.get();
        stateHolder.setCurrentMinReplicas(snapshot.minReplicas());
        int disasterMin = properties.getSurge().getDisasterMinReplicas();

        DisasterConditionResult condition = safeQueryCondition();

        if (condition == DisasterConditionResult.ACTIVE) {
            log.info("[PreScaling] startup: DB disaster ACTIVE — types={}, minLevelRank={}, lookbackMin={}. HPA minReplicas={}",
                    properties.getTrigger().getDisasterTypes(),
                    properties.getTrigger().getMinLevelRank(),
                    properties.getTrigger().getLookbackMinutes(),
                    snapshot.minReplicas());
            stateHolder.markDisasterActive(disasterMin);
            metrics.setActive(true, disasterMin);
            if (snapshot.minReplicas() < disasterMin) {
                log.info("[PreScaling] startup: patching HPA {} -> {}", snapshot.minReplicas(), disasterMin);
                hpaPatchService.patchMinReplicas(snapshot.minReplicas(), disasterMin, snapshot.maxReplicas());
            }
        } else if (condition == DisasterConditionResult.INACTIVE && snapshot.minReplicas() >= disasterMin) {
            // HPA is at surge level but no active disaster — controller restarted during cooldown.
            // Start a fresh cooldown so recovery doesn't happen immediately.
            log.info("[PreScaling] startup: no DB disaster but HPA minReplicas={} — entering cooldown", snapshot.minReplicas());
            stateHolder.restoreDisasterStateFromHpa(disasterMin);
            metrics.setActive(true, disasterMin);
        } else if (condition == DisasterConditionResult.UNKNOWN) {
            log.warn("[PreScaling] startup: DB UNKNOWN — holding current HPA state, minReplicas={}", snapshot.minReplicas());
        } else {
            log.info("[PreScaling] startup: no active disaster, HPA minReplicas={} — no action", snapshot.minReplicas());
            metrics.setActive(false, properties.getSurge().getNormalMinReplicas());
        }
    }

    @Scheduled(fixedDelayString = "${pre-scaling.polling-interval-ms:30000}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }

        DisasterConditionResult condition = safeQueryCondition();
        Optional<HpaSnapshot> snapshotOpt = hpaPatchService.getHpaSnapshot();
        if (snapshotOpt.isEmpty()) {
            log.warn("[PreScaling] HPA not found, skipping reconcile");
            return;
        }

        HpaSnapshot snapshot = snapshotOpt.get();
        int normalMin = properties.getSurge().getNormalMinReplicas();
        int disasterMin = properties.getSurge().getDisasterMinReplicas();
        long cooldownSeconds = properties.getSurge().getCooldownSeconds();

        log.debug("[PreScaling] reconcile: condition={}, HPA minReplicas={}, maxReplicas={}, controllerActive={}",
                condition, snapshot.minReplicas(), snapshot.maxReplicas(), stateHolder.getDisasterActive().get());

        switch (condition) {
            case ACTIVE -> handleDisasterActive(snapshot, disasterMin);
            case INACTIVE -> handleDisasterInactive(snapshot, normalMin, disasterMin, cooldownSeconds);
            case UNKNOWN -> handleUnknown(snapshot);
        }
    }

    private void handleDisasterActive(HpaSnapshot snapshot, int disasterMin) {
        if (!stateHolder.getDisasterActive().get()) {
            log.info("[PreScaling] TRIGGER: disaster ACTIVE — types={}, minLevelRank={}, lookbackMin={}. minReplicas {} -> {}",
                    properties.getTrigger().getDisasterTypes(),
                    properties.getTrigger().getMinLevelRank(),
                    properties.getTrigger().getLookbackMinutes(),
                    snapshot.minReplicas(), disasterMin);
            metrics.recordDecision();
            boolean ok = hpaPatchService.patchMinReplicas(snapshot.minReplicas(), disasterMin, snapshot.maxReplicas());
            if (ok) {
                stateHolder.markDisasterActive(disasterMin);
                metrics.setActive(true, disasterMin);
                metrics.recordTriggerTime(Instant.now().getEpochSecond());
            }
        } else if (snapshot.minReplicas() < disasterMin) {
            // HPA drifted below target (e.g., ArgoCD selfHeal without ignoreDifferences)
            log.warn("[PreScaling] HPA minReplicas drifted to {} below disasterMin={} — re-patching",
                    snapshot.minReplicas(), disasterMin);
            hpaPatchService.patchMinReplicas(snapshot.minReplicas(), disasterMin, snapshot.maxReplicas());
        }
    }

    private void handleDisasterInactive(HpaSnapshot snapshot, int normalMin, int disasterMin, long cooldownSeconds) {
        if (!stateHolder.getDisasterActive().get()) {
            return;
        }

        boolean cooldownElapsed = stateHolder.isCooldownElapsed(cooldownSeconds);
        Instant trigger = stateHolder.getLastTriggerTime().get();
        long elapsedSeconds = trigger != null
                ? Instant.now().getEpochSecond() - trigger.getEpochSecond()
                : cooldownSeconds;
        long remainingSeconds = Math.max(0, cooldownSeconds - elapsedSeconds);

        if (!cooldownElapsed) {
            log.info("[PreScaling] disaster INACTIVE but cooldown active: {}s remaining. HPA minReplicas stays at {}",
                    remainingSeconds, snapshot.minReplicas());
            return;
        }

        log.info("[PreScaling] RECOVER: disaster INACTIVE + cooldown elapsed ({}s). minReplicas {} -> {}",
                cooldownSeconds, snapshot.minReplicas(), normalMin);
        metrics.recordDecision();
        boolean ok = hpaPatchService.patchMinReplicas(snapshot.minReplicas(), normalMin, snapshot.maxReplicas());
        if (ok) {
            stateHolder.markRecovered(normalMin);
            metrics.setActive(false, normalMin);
            metrics.recordRecoverTime(Instant.now().getEpochSecond());
        }
    }

    private void handleUnknown(HpaSnapshot snapshot) {
        log.warn("[PreScaling] DB query UNKNOWN — holding current state. HPA minReplicas={}, controllerActive={}. " +
                        "Recovery patch suppressed until DB query succeeds.",
                snapshot.minReplicas(), stateHolder.getDisasterActive().get());
    }

    private DisasterConditionResult safeQueryCondition() {
        try {
            return alertRepository.queryCondition();
        } catch (Exception e) {
            log.error("[PreScaling] DB polling failed — result=UNKNOWN. types={}, minLevelRank={}, lookbackMin={}",
                    properties.getTrigger().getDisasterTypes(),
                    properties.getTrigger().getMinLevelRank(),
                    properties.getTrigger().getLookbackMinutes(), e);
            return DisasterConditionResult.UNKNOWN;
        }
    }
}
