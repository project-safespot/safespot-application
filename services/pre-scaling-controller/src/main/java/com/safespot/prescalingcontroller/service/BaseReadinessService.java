package com.safespot.prescalingcontroller.service;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BaseReadinessService {

    private static final long POLL_INTERVAL_MILLIS = 5_000L;

    private final KubernetesClient k8sClient;
    private final PreScalingProperties properties;

    public boolean hasAtLeastOneReadyPod() {
        String workloadName = properties.getBase().getTargetHpaName();
        Map<String, String> labels = Map.of(
                "app", workloadName,
                "service", workloadName
        );

        List<Pod> pods = k8sClient.pods()
                .inNamespace(properties.getNamespace())
                .withLabels(labels)
                .list()
                .getItems();

        return pods.stream().anyMatch(this::isReady);
    }

    public boolean waitForBaseReady(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (!Instant.now().isAfter(deadline)) {
            if (hasAtLeastOneReadyPod()) {
                return true;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Restore] interrupted while waiting for base readiness");
                return false;
            }
        }

        return false;
    }

    private boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }

        return pod.getStatus().getConditions().stream()
                .anyMatch(this::isReadyCondition);
    }

    private boolean isReadyCondition(PodCondition condition) {
        return "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus());
    }
}
