package com.safespot.prescalingcontroller.service;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Patches spec.minReplicas of HPA/api-public-read-surge.
 *
 * Controller responsibility is limited to HPA minReplicas patch only (§3).
 * Deployment replicas are never directly controlled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HpaPatchService {

    private final KubernetesClient k8sClient;
    private final PreScalingProperties properties;
    private final PreScalingMetrics metrics;

    /**
     * Reads the current HPA and returns minReplicas + maxReplicas in one GET call.
     * Returns empty if the HPA does not exist or has no spec.
     */
    public Optional<HpaSnapshot> getHpaSnapshot() {
        HorizontalPodAutoscaler hpa = hpaResource().get();

        if (hpa == null || hpa.getSpec() == null) {
            log.warn("[HPA] {}/{} not found or has no spec",
                    properties.getNamespace(), properties.getTargetHpaName());
            return Optional.empty();
        }

        Integer minReplicas = hpa.getSpec().getMinReplicas();
        int maxReplicas = hpa.getSpec().getMaxReplicas();
        int effectiveMin = minReplicas != null ? minReplicas : 0;
        return Optional.of(new HpaSnapshot(effectiveMin, maxReplicas));
    }

    /**
     * Patches spec.minReplicas using a JSON merge patch.
     * Guards against targetMinReplicas > HPA spec.maxReplicas.
     *
     * @return true if the patch succeeded
     */
    public boolean patchMinReplicas(int currentMinReplicas, int targetMinReplicas, int maxReplicas) {
        String namespace = properties.getNamespace();
        String hpaName = properties.getTargetHpaName();

        if (targetMinReplicas > maxReplicas) {
            log.warn("[HPA patch] BLOCKED: requestedMinReplicas={} exceeds HPA maxReplicas={}. No patch applied.",
                    targetMinReplicas, maxReplicas);
            metrics.recordPatchFailure();
            return false;
        }

        if (currentMinReplicas == targetMinReplicas) {
            log.debug("[HPA patch] no-op: minReplicas already at target={}", targetMinReplicas);
            return true;
        }

        String patchBody = "{\"spec\":{\"minReplicas\":" + targetMinReplicas + "}}";

        log.info("[HPA patch] {}/{} minReplicas: {} -> {}",
                namespace, hpaName, currentMinReplicas, targetMinReplicas);
        try {
            hpaResource().patch(new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE).build(), patchBody);

            metrics.recordPatchSuccess(targetMinReplicas);
            log.info("[HPA patch] success: {}/{} minReplicas={}", namespace, hpaName, targetMinReplicas);
            return true;
        } catch (Exception e) {
            metrics.recordPatchFailure();
            log.error("[HPA patch] failed: {}/{} targetMinReplicas={}", namespace, hpaName, targetMinReplicas, e);
            return false;
        }
    }

    /**
     * Package-private: isolated here so tests can spy and stub this single point
     * instead of mocking the full Fabric8 generic chain.
     */
    Resource<HorizontalPodAutoscaler> hpaResource() {
        return k8sClient.autoscaling().v2()
                .horizontalPodAutoscalers()
                .inNamespace(properties.getNamespace())
                .withName(properties.getTargetHpaName());
    }
}
