package com.safespot.prescalingcontroller.service;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreScalingRestoreServiceTest {

    @Mock private GenericHpaPatchService hpaPatchService;
    @Mock private IngressPatchService ingressPatchService;
    @Mock private BaseReadinessService baseReadinessService;

    private PreScalingStateHolder stateHolder;
    private PreScalingRestoreService service;

    @BeforeEach
    void setUp() {
        stateHolder = new PreScalingStateHolder();
        stateHolder.markDisasterSustained(3);
        service = new PreScalingRestoreService(
                hpaPatchService,
                ingressPatchService,
                baseReadinessService,
                stateHolder,
                new PreScalingProperties()
        );
    }

    @Test
    void restoreNormal_successfulFlow() {
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 5)).thenReturn(true);
        when(baseReadinessService.waitForBaseReady(java.time.Duration.ofSeconds(120))).thenReturn(true);
        when(ingressPatchService.patchWeights(100, 0)).thenReturn(true);
        when(hpaPatchService.patchMinReplicas("api-public-read-surge", 1)).thenReturn(true);

        RestoreNormalResult result = service.restoreNormal();

        assertThat(result.success()).isTrue();
        InOrder inOrder = inOrder(hpaPatchService, baseReadinessService, ingressPatchService);
        inOrder.verify(hpaPatchService).patchMinMaxReplicas("api-public-read", 1, 5);
        inOrder.verify(baseReadinessService).waitForBaseReady(java.time.Duration.ofSeconds(120));
        inOrder.verify(ingressPatchService).patchWeights(100, 0);
        inOrder.verify(hpaPatchService).patchMinReplicas("api-public-read-surge", 1);
        assertThat(stateHolder.getState().get()).isEqualTo(PreScalingStateHolder.State.NORMAL);
    }

    @Test
    void restoreNormal_readinessFailure_stopsBeforeRouting() {
        when(hpaPatchService.patchMinMaxReplicas("api-public-read", 1, 5)).thenReturn(true);
        when(baseReadinessService.waitForBaseReady(java.time.Duration.ofSeconds(120))).thenReturn(false);

        RestoreNormalResult result = service.restoreNormal();

        assertThat(result.success()).isFalse();
        assertThat(result.stage()).isEqualTo("BASE_READINESS");
        verify(ingressPatchService, never()).patchWeights(100, 0);
        verify(hpaPatchService, never()).patchMinReplicas("api-public-read-surge", 1);
    }
}
