package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.DisasterAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisasterAlertReadServiceTest {

    @Mock DisasterAlertRepository disasterAlertRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;

    @InjectMocks DisasterAlertReadService disasterAlertReadService;

    @Test
    void findAlerts_cacheHit_returnsFromCache() {
        List<DisasterAlertItem> cached = List.of(
                new DisasterAlertItem(55L, "FLOOD", "서울특별시", "주의", "메시지", "2026-04-14T08:55:00+09:00", null)
        );
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts("서울특별시", "FLOOD");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alertId()).isEqualTo(55L);
        verify(disasterAlertRepository, never()).findAlerts(any(), any());
    }

    @Test
    void findAlerts_cacheMiss_fallsBackToRds() {
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));

        DisasterAlert alert = mock(DisasterAlert.class);
        when(alert.getAlertId()).thenReturn(55L);
        when(alert.getDisasterType()).thenReturn("FLOOD");
        when(alert.getRegion()).thenReturn("서울특별시");
        when(alert.getLevel()).thenReturn("주의");
        when(alert.getMessage()).thenReturn("한강 수위 상승");
        when(alert.getIssuedAt()).thenReturn(OffsetDateTime.now());
        when(alert.getExpiredAt()).thenReturn(null);
        when(disasterAlertRepository.findAlerts("서울특별시", "FLOOD")).thenReturn(List.of(alert));
        when(suppressWindowService.tryPublish(any())).thenReturn(false);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts("서울특별시", "FLOOD");

        assertThat(result).hasSize(1);
        verify(redisReadCache).recordFallback(eq("/disaster-alerts"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
    }

    @Test
    void findAlerts_noFilter_returnsAll() {
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(disasterAlertRepository.findAlerts(null, null)).thenReturn(List.of());
        when(suppressWindowService.tryPublish(any())).thenReturn(false);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void findLatest_notFound_throwsApiException() {
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(disasterAlertRepository.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void findLatest_found_returnsDto() {
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));

        DisasterAlert alert = mock(DisasterAlert.class);
        when(alert.getAlertId()).thenReturn(55L);
        when(alert.getDisasterType()).thenReturn("EARTHQUAKE");
        when(alert.getRegion()).thenReturn("서울특별시");
        when(alert.getLevel()).thenReturn("주의");
        when(alert.getMessage()).thenReturn("지진 감지");
        when(alert.getIssuedAt()).thenReturn(OffsetDateTime.now());
        when(alert.getExpiredAt()).thenReturn(null);
        when(alert.getDetail()).thenReturn(null);
        when(disasterAlertRepository.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(Optional.of(alert));
        when(suppressWindowService.tryPublish(any())).thenReturn(false);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시");

        assertThat(result.alertId()).isEqualTo(55L);
        assertThat(result.disasterType()).isEqualTo("EARTHQUAKE");
    }
}
