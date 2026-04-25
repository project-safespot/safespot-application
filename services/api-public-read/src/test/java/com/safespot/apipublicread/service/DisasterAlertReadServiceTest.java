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

import static com.safespot.apipublicread.service.DisasterAlertReadService.DETAIL_KEY_PREFIX;
import static com.safespot.apipublicread.service.DisasterAlertReadService.LIST_KEY;
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

    private static final String DETAIL_KEY_55 = DETAIL_KEY_PREFIX + "55";

    private static final DisasterAlertItem EARTHQUAKE_ITEM = new DisasterAlertItem(
            55L, "EARTHQUAKE", "서울특별시", "주의", "지진 감지", "2026-04-14T08:55:00+09:00", null);
    private static final DisasterAlertItem FLOOD_ITEM = new DisasterAlertItem(
            56L, "FLOOD", "서울특별시", "경보", "한강 수위 상승", "2026-04-14T09:00:00+09:00", null);

    // ── findAlerts ─────────────────────────────────────────────────────────

    @Test
    void findAlerts_cacheHit_filterByType_returnsMatchingItems() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, "FLOOD");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alertId()).isEqualTo(56L);
        verify(disasterAlertRepository, never()).findAlerts(any(), any());
        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    @Test
    void findAlerts_cacheHit_noFilter_returnsAll() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, null);

        assertThat(result).hasSize(2);
        verify(disasterAlertRepository, never()).findAlerts(any(), any());
    }

    @Test
    void findAlerts_cacheMiss_fallsBackToRdsAndPublishes() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(disasterAlertRepository.findAlerts("서울특별시", "EARTHQUAKE")).thenReturn(List.of(alert));
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(true);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts("서울특별시", "EARTHQUAKE");

        assertThat(result).hasSize(1);
        verify(redisReadCache).recordFallback(eq("/disaster-alerts"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(LIST_KEY);
    }

    @Test
    void findAlerts_cacheMiss_rdsResultCappedAt50() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        List<DisasterAlert> rdsAlerts = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(i -> stubAlert(i, "FLOOD"))
                .toList();
        when(disasterAlertRepository.findAlerts(null, null)).thenReturn(rdsAlerts);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, null);

        assertThat(result).hasSize(50);
    }

    @Test
    void findAlerts_cacheMiss_suppressWindowPreventsDoublePublish() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(disasterAlertRepository.findAlerts(any(), any())).thenReturn(List.of());
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(false);

        disasterAlertReadService.findAlerts(null, null);

        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    // ── findLatest: list hit ───────────────────────────────────────────────

    @Test
    void findLatest_listHit_typeMatch_detailHit_returnsFromCache() {
        DisasterLatestDto cached = new DisasterLatestDto(55L, "EARTHQUAKE", "서울특별시", "주의",
                "지진 감지", "2026-04-14T08:55:00+09:00", null, null);

        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(disasterAlertRepository, never()).findLatest(any(), any());
        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    @Test
    void findLatest_listHit_typeMatch_detailMiss_fallsBackAndPublishesDetailKey() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(disasterAlertRepository.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(Optional.of(alert));
        when(suppressWindowService.tryPublish(DETAIL_KEY_55)).thenReturn(true);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(redisReadCache).recordFallback(eq("/disasters/{disasterType}/latest"),
                eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(DETAIL_KEY_55);
        verify(cacheRegenerationPublisher, never()).publish(LIST_KEY);
    }

    @Test
    void findLatest_listHit_noTypeMatch_throwsNotFound() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(FLOOD_ITEM), null));

        assertThatThrownBy(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시"))
                .isInstanceOf(ApiException.class);

        verify(disasterAlertRepository, never()).findLatest(any(), any());
        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    // ── findLatest: list miss ──────────────────────────────────────────────

    @Test
    void findLatest_listMiss_fallsBackToRdsAndPublishesListKey() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(disasterAlertRepository.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(Optional.of(alert));
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(true);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(redisReadCache).recordFallback(eq("/disasters/{disasterType}/latest"),
                eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(LIST_KEY);
    }

    @Test
    void findLatest_listMiss_notFound_throwsApiException() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(disasterAlertRepository.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(Optional.empty());
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(false);

        assertThatThrownBy(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시"))
                .isInstanceOf(ApiException.class);
    }

    private DisasterAlert stubAlert(long alertId, String disasterType) {
        DisasterAlert alert = mock(DisasterAlert.class);
        lenient().when(alert.getAlertId()).thenReturn(alertId);
        lenient().when(alert.getDisasterType()).thenReturn(disasterType);
        lenient().when(alert.getRegion()).thenReturn("서울특별시");
        lenient().when(alert.getLevel()).thenReturn("주의");
        lenient().when(alert.getMessage()).thenReturn("지진 감지");
        lenient().when(alert.getIssuedAt()).thenReturn(OffsetDateTime.now());
        lenient().when(alert.getExpiredAt()).thenReturn(null);
        lenient().when(alert.getDetail()).thenReturn(null);
        return alert;
    }
}
