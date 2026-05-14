package com.safespot.apipublicread.service;

import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.dto.DisasterCacheWarmupRequest;
import com.safespot.apipublicread.event.DisasterWarmupPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// disaster read model은 region 차원 없이 서울 전용 global warmup만 지원한다.
// DisasterCacheWarmupRequest는 region 없이 {limit, includeDetails}만 포함한다.
@ExtendWith(MockitoExtension.class)
class DisasterCacheWarmupServiceTest {

    @Mock private SuppressWindowService suppressWindowService;
    @Mock private DisasterWarmupPublisher disasterWarmupPublisher;

    @InjectMocks private DisasterCacheWarmupService service;

    @Test
    void requestWarmup_전체_warmup_suppressWindowOpen_publishes_returnsTrue() {
        when(suppressWindowService.tryPublish("disaster:warmup:all")).thenReturn(true);
        DisasterCacheWarmupRequest request = new DisasterCacheWarmupRequest(10, true);

        boolean result = service.requestWarmup(request);

        assertThat(result).isTrue();
        verify(disasterWarmupPublisher).publish(10, true);
    }

    @Test
    void requestWarmup_suppressWindowClosed_doesNotPublish_returnsFalse() {
        when(suppressWindowService.tryPublish("disaster:warmup:all")).thenReturn(false);
        DisasterCacheWarmupRequest request = new DisasterCacheWarmupRequest(10, true);

        boolean result = service.requestWarmup(request);

        assertThat(result).isFalse();
        verify(disasterWarmupPublisher, never()).publish(anyInt(), anyBoolean());
    }

    @Test
    void requestWarmup_includeDetails_false_passedThrough() {
        when(suppressWindowService.tryPublish(eq("disaster:warmup:all"))).thenReturn(true);
        DisasterCacheWarmupRequest request = new DisasterCacheWarmupRequest(5, false);

        service.requestWarmup(request);

        verify(disasterWarmupPublisher).publish(5, false);
    }
}
