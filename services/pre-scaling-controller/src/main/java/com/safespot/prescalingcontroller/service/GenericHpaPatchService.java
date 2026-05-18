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

@Service
@RequiredArgsConstructor
@Slf4j
public class GenericHpaPatchService {

    private final KubernetesClient k8sClient;
    private final PreScalingProperties properties;
    private final PreScalingMetrics metrics;

    public Optional<HpaSnapshot> getHpaSnapshot(String hpaName) {
        HorizontalPodAutoscaler hpa = hpaResource(hpaName).get();
        if (hpa == null || hpa.getSpec() == null) {
            log.warn("[HPA] {}/{} not found or has no spec", properties.getNamespace(), hpaName);
            return Optional.empty();
        }

        Integer minReplicas = hpa.getSpec().getMinReplicas();
        int maxReplicas = hpa.getSpec().getMaxReplicas();
        return Optional.of(new HpaSnapshot(minReplicas != null ? minReplicas : 0, maxReplicas));
    }

    public boolean patchMinReplicas(String hpaName, int targetMinReplicas) {
        Optional<HpaSnapshot> snapshotOpt = getHpaSnapshot(hpaName);
        if (snapshotOpt.isEmpty()) {
            metrics.recordPatchFailure();
            return false;
        }

        HpaSnapshot snapshot = snapshotOpt.get();
        if (targetMinReplicas > snapshot.maxReplicas()) {
            log.warn("[HPA patch] blocked: requested minReplicas={} exceeds current maxReplicas={} for {}/{}",
                    targetMinReplicas, snapshot.maxReplicas(), properties.getNamespace(), hpaName);
            metrics.recordPatchFailure();
            return false;
        }

        if (snapshot.minReplicas() == targetMinReplicas) {
            return true;
        }

        String patchBody = "{\"spec\":{\"minReplicas\":" + targetMinReplicas + "}}";
        return patch(hpaName, patchBody, "minReplicas=" + targetMinReplicas, targetMinReplicas);
    }

    public boolean patchMinMaxReplicas(String hpaName, int targetMinReplicas, int targetMaxReplicas) {
        if (targetMinReplicas > targetMaxReplicas) {
            log.warn("[HPA patch] blocked: minReplicas={} exceeds maxReplicas={} for {}/{}",
                    targetMinReplicas, targetMaxReplicas, properties.getNamespace(), hpaName);
            metrics.recordPatchFailure();
            return false;
        }

        Optional<HpaSnapshot> snapshotOpt = getHpaSnapshot(hpaName);
        if (snapshotOpt.isEmpty()) {
            metrics.recordPatchFailure();
            return false;
        }

        HpaSnapshot snapshot = snapshotOpt.get();
        if (snapshot.minReplicas() == targetMinReplicas && snapshot.maxReplicas() == targetMaxReplicas) {
            return true;
        }

        String patchBody = "{\"spec\":{\"minReplicas\":" + targetMinReplicas
                + ",\"maxReplicas\":" + targetMaxReplicas + "}}";
        return patch(hpaName, patchBody,
                "minReplicas=" + targetMinReplicas + ", maxReplicas=" + targetMaxReplicas,
                targetMinReplicas);
    }

    Resource<HorizontalPodAutoscaler> hpaResource(String hpaName) {
        return k8sClient.autoscaling().v2()
                .horizontalPodAutoscalers()
                .inNamespace(properties.getNamespace())
                .withName(hpaName);
    }

    private boolean patch(String hpaName, String patchBody, String targetDescription, int targetMinReplicas) {
        try {
            hpaResource(hpaName).patch(
                    new PatchContext.Builder().withPatchType(PatchType.JSON_MERGE).build(),
                    patchBody
            );
            metrics.recordPatchSuccess(targetMinReplicas);
            log.info("[HPA patch] success: {}/{} {}", properties.getNamespace(), hpaName, targetDescription);
            return true;
        } catch (Exception e) {
            metrics.recordPatchFailure();
            log.error("[HPA patch] failed: {}/{} {}", properties.getNamespace(), hpaName, targetDescription, e);
            return false;
        }
    }
}
