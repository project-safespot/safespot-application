package com.safespot.prescalingcontroller.service;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import com.safespot.prescalingcontroller.metrics.PreScalingMetrics;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HpaPatchService.
 *
 * HpaPatchService.hpaResource() is spied and stubbed to avoid chaining
 * the entire Fabric8 generic API (RETURNS_DEEP_STUBS fails on MixedOperation
 * generic chain in Fabric8 6.x).
 */
@ExtendWith(MockitoExtension.class)
class HpaPatchServiceTest {

    @Mock private KubernetesClient k8sClient;
    @Mock private PreScalingMetrics metrics;
    @Mock private Resource<HorizontalPodAutoscaler> hpaResource;

    private HpaPatchService service;

    @BeforeEach
    void setUp() {
        PreScalingProperties properties = buildProperties();
        // Spy so we can override the single Fabric8 chain method.
        // lenient: some tests (maxReplicas guard, no-op) return before calling hpaResource().
        service = spy(new HpaPatchService(k8sClient, properties, metrics));
        lenient().doReturn(hpaResource).when(service).hpaResource();
    }

    // ── getHpaSnapshot ────────────────────────────────────────────────────────

    @Test
    void getHpaSnapshot_returnsMinAndMax() {
        when(hpaResource.get()).thenReturn(buildHpa(0, 10));

        Optional<HpaSnapshot> result = service.getHpaSnapshot();

        assertThat(result).isPresent();
        assertThat(result.get().minReplicas()).isEqualTo(0);
        assertThat(result.get().maxReplicas()).isEqualTo(10);
    }

    @Test
    void getHpaSnapshot_hpaNotFound_returnsEmpty() {
        when(hpaResource.get()).thenReturn(null);

        Optional<HpaSnapshot> result = service.getHpaSnapshot();

        assertThat(result).isEmpty();
    }

    @Test
    void getHpaSnapshot_nullMinReplicas_treatedAsZero() {
        when(hpaResource.get()).thenReturn(buildHpa(null, 10));

        Optional<HpaSnapshot> result = service.getHpaSnapshot();

        assertThat(result).isPresent();
        assertThat(result.get().minReplicas()).isEqualTo(0);
    }

    // ── patchMinReplicas: success ─────────────────────────────────────────────

    @Test
    void patchMinReplicas_success_returnsTrue() {
        boolean result = service.patchMinReplicas(0, 3, 10);

        assertThat(result).isTrue();
        verify(hpaResource).patch(any(), eq("{\"spec\":{\"minReplicas\":3}}"));
        verify(metrics).recordPatchSuccess(3);
    }

    // ── patchMinReplicas: no-op when already at target ────────────────────────

    @Test
    void patchMinReplicas_currentEqualsTarget_noPatch() {
        boolean result = service.patchMinReplicas(3, 3, 10);

        assertThat(result).isTrue();
        verify(hpaResource, never()).patch(any(), anyString());
        verify(metrics, never()).recordPatchSuccess(anyInt());
    }

    // ── patchMinReplicas: maxReplicas guard ───────────────────────────────────

    @Test
    void patchMinReplicas_targetExceedsMaxReplicas_blocked() {
        boolean result = service.patchMinReplicas(0, 15, 10);

        assertThat(result).isFalse();
        verify(metrics).recordPatchFailure();
        verify(hpaResource, never()).patch(any(), anyString());
    }

    @Test
    void patchMinReplicas_targetEqualsMaxReplicas_allowed() {
        boolean result = service.patchMinReplicas(0, 10, 10);

        assertThat(result).isTrue();
        verify(hpaResource).patch(any(), eq("{\"spec\":{\"minReplicas\":10}}"));
    }

    // ── patchMinReplicas: k8s failure ─────────────────────────────────────────

    @Test
    void patchMinReplicas_k8sException_returnsFalse() {
        when(hpaResource.patch(any(), anyString())).thenThrow(new RuntimeException("k8s unavailable"));

        boolean result = service.patchMinReplicas(0, 3, 10);

        assertThat(result).isFalse();
        verify(metrics).recordPatchFailure();
    }

    // ── patchMinReplicas: metric counters ─────────────────────────────────────

    @Test
    void patchMinReplicas_success_incrementsPatchSuccessMetric() {
        service.patchMinReplicas(0, 3, 10);

        verify(metrics).recordPatchSuccess(3);
        verify(metrics, never()).recordPatchFailure();
    }

    @Test
    void patchMinReplicas_failure_incrementsPatchFailedMetric() {
        when(hpaResource.patch(any(), anyString())).thenThrow(new RuntimeException("err"));

        service.patchMinReplicas(0, 3, 10);

        verify(metrics).recordPatchFailure();
        verify(metrics, never()).recordPatchSuccess(anyInt());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HorizontalPodAutoscaler buildHpa(Integer minReplicas, int maxReplicas) {
        HorizontalPodAutoscalerSpec spec = new HorizontalPodAutoscalerSpec();
        spec.setMinReplicas(minReplicas);
        spec.setMaxReplicas(maxReplicas);
        HorizontalPodAutoscaler hpa = new HorizontalPodAutoscaler();
        hpa.setSpec(spec);
        return hpa;
    }

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
        surge.setNormalMinReplicas(0);
        surge.setDisasterMinReplicas(3);
        surge.setMaxReplicas(10);
        surge.setCooldownSeconds(1800);
        props.setSurge(surge);

        return props;
    }
}
