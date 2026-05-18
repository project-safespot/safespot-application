package com.safespot.prescalingcontroller.service;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreScalingRestoreService {

    private final GenericHpaPatchService hpaPatchService;
    private final IngressPatchService ingressPatchService;
    private final BaseReadinessService baseReadinessService;
    private final PreScalingStateHolder stateHolder;
    private final PreScalingProperties properties;

    public RestoreNormalResult restoreNormal() {
        PreScalingProperties.Base base = properties.getBase();
        PreScalingProperties.Surge surge = properties.getSurge();
        PreScalingProperties.Routing routing = properties.getRouting();

        boolean basePatched = !base.isEnabled() || hpaPatchService.patchMinMaxReplicas(
                base.getTargetHpaName(),
                base.getNormalMinReplicas(),
                base.getNormalMaxReplicas()
        );
        if (!basePatched) {
            log.warn("[Restore] failed at BASE_HPA_RESTORE");
            return RestoreNormalResult.failure("BASE_HPA_RESTORE", "Failed to restore base HPA min/max");
        }

        Duration timeout = Duration.ofSeconds(properties.getRestore().getBaseReadinessTimeoutSeconds());
        if (!baseReadinessService.waitForBaseReady(timeout)) {
            log.warn("[Restore] failed at BASE_READINESS");
            return RestoreNormalResult.failure("BASE_READINESS", "Base pod readiness did not become healthy in time");
        }

        boolean routingPatched = !routing.isEnabled() || ingressPatchService.patchWeights(
                routing.getNormalBaseWeight(),
                routing.getNormalSurgeWeight()
        );
        if (!routingPatched) {
            log.warn("[Restore] failed at ROUTING_RESTORE");
            return RestoreNormalResult.failure("ROUTING_RESTORE", "Failed to restore base/surge routing weights");
        }

        boolean surgePatched = hpaPatchService.patchMinReplicas(
                surge.getTargetHpaName(),
                surge.getNormalMinReplicas()
        );
        if (!surgePatched) {
            log.warn("[Restore] failed at SURGE_HPA_RESTORE");
            return RestoreNormalResult.failure("SURGE_HPA_RESTORE", "Failed to restore surge HPA minReplicas");
        }

        stateHolder.markNormal(surge.getNormalMinReplicas());
        log.info("[Restore] normal state restored successfully");
        return RestoreNormalResult.success("Normal state restored");
    }
}
