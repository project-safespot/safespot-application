package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.DisasterCacheWarmupRequest;
import com.safespot.apipublicread.service.DisasterCacheWarmupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalCacheWarmupControllerTest {

    @Mock private DisasterCacheWarmupService warmupService;

    private InternalCacheWarmupController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalCacheWarmupController(warmupService);
        ReflectionTestUtils.setField(controller, "warmupSecret", "test-secret-value");
    }

    @Test
    void warmupDisasters_올바른_secret_publish_성공_202() {
        when(warmupService.requestWarmup(any())).thenReturn(true);

        ResponseEntity<?> response = controller.warmupDisasters(
                "test-secret-value", new DisasterCacheWarmupRequest(10, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(warmupService).requestWarmup(any());
    }

    @Test
    void warmupDisasters_잘못된_secret_401_service_미호출() {
        ResponseEntity<?> response = controller.warmupDisasters(
                "wrong-secret", new DisasterCacheWarmupRequest(10, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(warmupService, never()).requestWarmup(any());
    }

    @Test
    void warmupDisasters_secret_헤더_없음_401() {
        ResponseEntity<?> response = controller.warmupDisasters(
                null, new DisasterCacheWarmupRequest(10, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(warmupService, never()).requestWarmup(any());
    }

    @Test
    void warmupDisasters_secret_property_미설정_blank_401() {
        ReflectionTestUtils.setField(controller, "warmupSecret", "");

        ResponseEntity<?> response = controller.warmupDisasters(
                "any-secret", new DisasterCacheWarmupRequest(10, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(warmupService, never()).requestWarmup(any());
    }

    @Test
    void warmupDisasters_suppress_window_활성_429() {
        when(warmupService.requestWarmup(any())).thenReturn(false);

        ResponseEntity<?> response = controller.warmupDisasters(
                "test-secret-value", new DisasterCacheWarmupRequest(10, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void controller_ConditionalOnProperty_warmup_enabled_true_로만_활성화됨() {
        ConditionalOnProperty annotation = InternalCacheWarmupController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains("safespot.warmup.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
        // matchIfMissing 기본값 false → property 없으면 controller bean 미생성
        assertThat(annotation.matchIfMissing()).isFalse();
    }
}
