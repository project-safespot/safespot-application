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
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenericHpaPatchServiceTest {

    @Mock private KubernetesClient k8sClient;
    @Mock private PreScalingMetrics metrics;

    private Resource<HorizontalPodAutoscaler> surgeResource;
    private Resource<HorizontalPodAutoscaler> baseResource;
    private GenericHpaPatchService service;

    @BeforeEach
    void setUp() {
        surgeResource = mock(Resource.class);
        baseResource = mock(Resource.class);

        service = spy(new GenericHpaPatchService(k8sClient, buildProperties(), metrics));
        lenient().doAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "api-public-read-surge" -> surgeResource;
            case "api-public-read" -> baseResource;
            default -> throw new IllegalArgumentException("unexpected hpa");
        }).when(service).hpaResource(anyString());
    }

    @Test
    void surgePatchMinReplicas_to8_success() {
        when(surgeResource.get()).thenReturn(buildHpa(1, 20));

        boolean result = service.patchMinReplicas("api-public-read-surge", 8);

        assertThat(result).isTrue();
        verify(surgeResource).patch(any(), eq("{\"spec\":{\"minReplicas\":8}}"));
    }

    @Test
    void surgePatchMinReplicas_to3_success() {
        when(surgeResource.get()).thenReturn(buildHpa(8, 20));

        boolean result = service.patchMinReplicas("api-public-read-surge", 3);

        assertThat(result).isTrue();
        verify(surgeResource).patch(any(), eq("{\"spec\":{\"minReplicas\":3}}"));
    }

    @Test
    void surgePatchMinReplicas_to1_success() {
        when(surgeResource.get()).thenReturn(buildHpa(3, 20));

        boolean result = service.patchMinReplicas("api-public-read-surge", 1);

        assertThat(result).isTrue();
        verify(surgeResource).patch(any(), eq("{\"spec\":{\"minReplicas\":1}}"));
    }

    @Test
    void basePatchMinMax_toOneOne_success() {
        when(baseResource.get()).thenReturn(buildHpa(1, 5));

        boolean result = service.patchMinMaxReplicas("api-public-read", 1, 1);

        assertThat(result).isTrue();
        verify(baseResource).patch(any(), eq("{\"spec\":{\"minReplicas\":1,\"maxReplicas\":1}}"));
    }

    @Test
    void basePatchMinMax_toOneFive_success() {
        when(baseResource.get()).thenReturn(buildHpa(1, 1));

        boolean result = service.patchMinMaxReplicas("api-public-read", 1, 5);

        assertThat(result).isTrue();
        verify(baseResource).patch(any(), eq("{\"spec\":{\"minReplicas\":1,\"maxReplicas\":5}}"));
    }

    @Test
    void patchMinMax_minGreaterThanMax_returnsFalse() {
        boolean result = service.patchMinMaxReplicas("api-public-read", 6, 5);

        assertThat(result).isFalse();
        verify(baseResource, never()).patch(any(), anyString());
    }

    @Test
    void patchMinReplicas_kubernetesException_returnsFalse() {
        when(surgeResource.get()).thenReturn(buildHpa(1, 20));
        when(surgeResource.patch(any(), anyString())).thenThrow(new RuntimeException("k8s unavailable"));

        boolean result = service.patchMinReplicas("api-public-read-surge", 8);

        assertThat(result).isFalse();
        verify(metrics).recordPatchFailure();
    }

    @Test
    void getHpaSnapshot_returnsMinMax() {
        when(surgeResource.get()).thenReturn(buildHpa(3, 20));

        Optional<HpaSnapshot> snapshot = service.getHpaSnapshot("api-public-read-surge");

        assertThat(snapshot).contains(new HpaSnapshot(3, 20));
    }

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

        PreScalingProperties.Trigger trigger = new PreScalingProperties.Trigger();
        trigger.setDisasterTypes(List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE"));
        trigger.setMinLevelRank(3);
        trigger.setLookbackMinutes(10);
        props.setTrigger(trigger);

        return props;
    }
}
