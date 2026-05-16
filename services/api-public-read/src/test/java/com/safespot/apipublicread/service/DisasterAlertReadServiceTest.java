package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.dto.cache.DisasterDetailCacheDto;
import com.safespot.apipublicread.dto.cache.DisasterMessageCacheDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.DisasterAlertRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.safespot.apipublicread.service.DisasterAlertReadService.DETAIL_KEY_PREFIX;
import static com.safespot.apipublicread.service.DisasterAlertReadService.LIST_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisasterAlertReadServiceTest {

    @Mock DisasterAlertRepository disasterAlertRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Spy FallbackSingleFlight fallbackSingleFlight = new FallbackSingleFlight(new SimpleMeterRegistry(), 2_000);

    @InjectMocks DisasterAlertReadService disasterAlertReadService;

    private static final String DETAIL_KEY_55 = DETAIL_KEY_PREFIX + "55";
    private static final String DB_FALLBACK_KEY_55 = "db-fallback:disaster:detail:" + DETAIL_KEY_55;

    private static final DisasterMessageCacheDto EARTHQUAKE_ITEM = new DisasterMessageCacheDto(
            1, 55L, "EARTHQUAKE", "quake", "ALERT", "WARN", 2,
            "seoul", "2026-04-14T08:55:00+09:00", null, "quake message", "MOIS", true);
    private static final DisasterMessageCacheDto FLOOD_ITEM = new DisasterMessageCacheDto(
            1, 56L, "FLOOD", "flood", "ALERT", "DANGER", 3,
            "seoul", "2026-04-14T09:00:00+09:00", null, "flood message", "MOIS", true);

    @Test
    void findAlerts_cacheHit_filterByType_returnsMatchingItems() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, "FLOOD");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alertId()).isEqualTo(56L);
        verify(disasterAlertRepository, never()).findAlerts(any(), any(), any());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findAlerts_cacheHit_noFilter_returnsAll() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, null);

        assertThat(result).hasSize(2);
        verify(disasterAlertRepository, never()).findAlerts(any(), any(), any());
    }

    @Test
    void findAlerts_cacheMiss_fallsBackToRdsAndPublishes() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(disasterAlertRepository.findAlerts(isNull(), isNull(), any(PageRequest.class))).thenReturn(List.of(alert));
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(true);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts("seoul", "EARTHQUAKE");

        assertThat(result).hasSize(1);
        verify(redisReadCache).recordFallback(eq("disaster_messages"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(LIST_KEY, CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");
    }

    @Test
    void findAlerts_cacheMiss_repositoryCalledWithPageableAndReturns50() {
        PageRequest expectedPage = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "issuedAt"));
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        List<DisasterAlert> rdsAlerts = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(i -> stubAlert(i, "FLOOD"))
                .toList();
        when(disasterAlertRepository.findAlerts(null, null, expectedPage)).thenReturn(rdsAlerts);

        List<DisasterAlertItem> result = disasterAlertReadService.findAlerts(null, null);

        assertThat(result).hasSize(50);
        verify(disasterAlertRepository).findAlerts(null, null, expectedPage);
    }

    @Test
    void findAlerts_cacheMiss_suppressWindowPreventsDoublePublish() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        when(disasterAlertRepository.findAlerts(any(), any(), any(PageRequest.class))).thenReturn(List.of());
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(false);

        disasterAlertReadService.findAlerts(null, null);

        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findAlerts_parseError_fallsBackWithoutRegenerationRequest() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "disaster_messages"));
        when(disasterAlertRepository.findAlerts(any(), any(), any(PageRequest.class))).thenReturn(List.of());

        disasterAlertReadService.findAlerts(null, null);

        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findLatest_listHit_typeMatch_detailHit_returnsFromCache() {
        DisasterDetailCacheDto cached = new DisasterDetailCacheDto(
                1, 55L, "EARTHQUAKE", "quake", "ALERT", "WARN", 2,
                "seoul", "2026-04-14T08:55:00+09:00", null, "quake message", "MOIS", true);

        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM, FLOOD_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "seoul");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(disasterAlertRepository, never()).findAlerts(any(), any(), any(PageRequest.class));
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findLatest_listHit_typeMatch_detailMiss_fallsBackAndPublishesDetailKey() {
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_detail"));
        when(disasterAlertRepository.findById(55L)).thenReturn(Optional.of(alert));
        when(suppressWindowService.tryPublish(DETAIL_KEY_55)).thenReturn(true);
        when(suppressWindowService.tryAllowDbFallback(DB_FALLBACK_KEY_55)).thenReturn(true);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "seoul");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(redisReadCache).recordFallback(eq("disaster_detail"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(DETAIL_KEY_55, CacheRegenerationReason.CACHE_MISS, "/disasters/{disasterType}/latest");
        verify(cacheRegenerationPublisher, never()).publish(eq(LIST_KEY), any(), anyString());
    }

    @Test
    void findLatest_listHit_detailMiss_dbFallbackSuppressed_returnsStaleListPayload() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_detail"));
        when(suppressWindowService.tryPublish(DETAIL_KEY_55)).thenReturn(true);
        when(suppressWindowService.tryAllowDbFallback(DB_FALLBACK_KEY_55)).thenReturn(false);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "seoul");

        assertThat(result.alertId()).isEqualTo(55L);
        assertThat(result.message()).isEqualTo(EARTHQUAKE_ITEM.message());
        verify(disasterAlertRepository, never()).findById(anyLong());
    }

    @Test
    void findLatest_listHit_detailMiss_joinTimeout_returnsStaleListPayload() throws Exception {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(EARTHQUAKE_ITEM), null));
        when(redisReadCache.get(eq(DETAIL_KEY_55), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_detail"));
        when(suppressWindowService.tryPublish(DETAIL_KEY_55)).thenReturn(true);
        when(suppressWindowService.tryAllowDbFallback(DB_FALLBACK_KEY_55)).thenReturn(true);

        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        when(disasterAlertRepository.findById(55L)).thenAnswer(invocation -> {
            leaderStarted.countDown();
            if (!releaseLeader.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
            return Optional.of(stubAlert(55L, "EARTHQUAKE"));
        });

        Thread leader = new Thread(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "seoul"));
        leader.start();
        assertThat(leaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

        DisasterLatestDto followerResult = disasterAlertReadService.findLatest("EARTHQUAKE", "seoul");

        releaseLeader.countDown();
        leader.join(1_000);

        assertThat(followerResult.alertId()).isEqualTo(55L);
        assertThat(followerResult.message()).isEqualTo(EARTHQUAKE_ITEM.message());
        verify(disasterAlertRepository, times(1)).findById(55L);
    }

    @Test
    void findLatest_listHit_noTypeMatch_throwsNotFound() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(FLOOD_ITEM), null));

        assertThatThrownBy(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "seoul"))
                .isInstanceOf(ApiException.class);

        verify(disasterAlertRepository, never()).findAlerts(any(), any(), any(PageRequest.class));
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findLatest_listMiss_fallsBackToRdsAndPublishesListKey() {
        DisasterAlert alert = stubAlert(55L, "EARTHQUAKE");
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        when(disasterAlertRepository.findAlerts(isNull(), isNull(), any(PageRequest.class))).thenReturn(List.of(alert));
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(true);

        DisasterLatestDto result = disasterAlertReadService.findLatest("EARTHQUAKE", "seoul");

        assertThat(result.alertId()).isEqualTo(55L);
        verify(redisReadCache).recordFallback(eq("disaster_messages"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(LIST_KEY, CacheRegenerationReason.CACHE_MISS, "/disasters/{disasterType}/latest");
    }

    @Test
    void findLatest_listMiss_notFound_throwsApiException() {
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        when(disasterAlertRepository.findAlerts(isNull(), isNull(), any(PageRequest.class))).thenReturn(List.of());
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(false);

        assertThatThrownBy(() -> disasterAlertReadService.findLatest("EARTHQUAKE", "seoul"))
                .isInstanceOf(ApiException.class);
    }

    private DisasterAlert stubAlert(long alertId, String disasterType) {
        DisasterAlert alert = mock(DisasterAlert.class);
        lenient().when(alert.getAlertId()).thenReturn(alertId);
        lenient().when(alert.getDisasterType()).thenReturn(disasterType);
        lenient().when(alert.getRegion()).thenReturn("seoul");
        lenient().when(alert.getLevel()).thenReturn("WARN");
        lenient().when(alert.getMessage()).thenReturn("quake message");
        lenient().when(alert.getIssuedAt()).thenReturn(OffsetDateTime.now());
        lenient().when(alert.getExpiredAt()).thenReturn(null);
        lenient().when(alert.getDetail()).thenReturn(null);
        return alert;
    }
}
