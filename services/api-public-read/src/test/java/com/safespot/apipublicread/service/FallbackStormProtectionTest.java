package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.DistributedFallbackGuard;
import com.safespot.apipublicread.cache.FallbackControlProperties;
import com.safespot.apipublicread.cache.PublicReadMetricRecorder;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.cache.DisasterMessageCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.repository.DisasterAlertRepository;
import com.safespot.apipublicread.repository.EvacuationEntryRepository;
import com.safespot.apipublicread.repository.ShelterRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.safespot.apipublicread.service.DisasterAlertReadService.LIST_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FallbackStormProtectionTest {

    @Test
    void sameShelterStatusMiss_concurrentRequestsPerformOneRepositoryFallback() throws Exception {
        ShelterRepository shelterRepository = mock(ShelterRepository.class);
        EvacuationEntryRepository evacuationEntryRepository = mock(EvacuationEntryRepository.class);
        RedisReadCache redisReadCache = mock(RedisReadCache.class);
        DistributedFallbackGuard distributedFallbackGuard = mock(DistributedFallbackGuard.class);
        SuppressWindowService suppressWindowService = mock(SuppressWindowService.class);
        CacheRegenerationPublisher publisher = mock(CacheRegenerationPublisher.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ShelterReadService service = new ShelterReadService(
                shelterRepository,
                evacuationEntryRepository,
                redisReadCache,
                new FallbackSingleFlight(meterRegistry, 1_000),
                distributedFallbackGuard,
                suppressWindowService,
                publisher,
                new PublicReadMetricRecorder(meterRegistry),
                new FallbackControlProperties()
        );
        when(distributedFallbackGuard.tryAcquire(anyString(), anyString(), anyString(), any()))
                .thenReturn(DistributedFallbackGuard.Decision.LEADER);
        Shelter shelter = shelter(101L);
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<ShelterStatusCacheDto>(
                        null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status"));
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(false);
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenAnswer(invocation -> {
            sleep(100);
            return 68L;
        });

        List<ShelterDetailDto> results = runConcurrent(100, () -> service.findById(101L));

        assertThat(results).hasSize(100);
        assertThat(results).allSatisfy(result -> assertThat(result.currentOccupancy()).isEqualTo(68));
        verify(evacuationEntryRepository, times(1)).countCurrentOccupancy(101L);
        verify(redisReadCache, times(1))
                .recordDbFallbackQuery("shelter_status", "shelter_status_repository", RedisReadCache.FallbackReason.REDIS_MISS, "leader");
    }

    @Test
    void sameDisasterMessagesMiss_concurrentRequestsPerformOneRepositoryFallback() throws Exception {
        DisasterAlertRepository disasterAlertRepository = mock(DisasterAlertRepository.class);
        RedisReadCache redisReadCache = mock(RedisReadCache.class);
        DistributedFallbackGuard distributedFallbackGuard = mock(DistributedFallbackGuard.class);
        SuppressWindowService suppressWindowService = mock(SuppressWindowService.class);
        CacheRegenerationPublisher publisher = mock(CacheRegenerationPublisher.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DisasterAlertReadService service = new DisasterAlertReadService(
                disasterAlertRepository,
                redisReadCache,
                new FallbackSingleFlight(meterRegistry, 1_000),
                distributedFallbackGuard,
                suppressWindowService,
                publisher,
                new PublicReadMetricRecorder(meterRegistry),
                new FallbackControlProperties()
        );
        when(distributedFallbackGuard.tryAcquire(anyString(), anyString(), anyString(), any()))
                .thenReturn(DistributedFallbackGuard.Decision.LEADER);
        when(redisReadCache.get(eq(LIST_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<List<DisasterMessageCacheDto>>(
                        null, RedisReadCache.FallbackReason.REDIS_MISS, "disaster_messages"));
        when(suppressWindowService.tryPublish(LIST_KEY)).thenReturn(false);
        DisasterAlert alert = alert(55L);
        when(disasterAlertRepository.findAlerts(isNull(), isNull(), any(PageRequest.class)))
                .thenAnswer(invocation -> {
                    sleep(100);
                    return List.of(alert);
                });

        List<List<DisasterAlertItem>> results = runConcurrent(100, () -> service.findAlerts("seoul", "EARTHQUAKE"));

        assertThat(results).hasSize(100);
        assertThat(results).allSatisfy(result -> assertThat(result).hasSize(1));
        verify(disasterAlertRepository, times(1))
                .findAlerts(isNull(), isNull(), any(PageRequest.class));
        verify(redisReadCache, times(1))
                .recordDbFallbackQuery("disaster_messages", "disaster_alert_repository", RedisReadCache.FallbackReason.REDIS_MISS, "leader");
    }

    private static <T> List<T> runConcurrent(int count, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                tasks.add(() -> {
                    start.await();
                    return task.call();
                });
            }
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(2, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Shelter shelter(long shelterId) {
        Shelter shelter = mock(Shelter.class);
        when(shelter.getShelterId()).thenReturn(shelterId);
        when(shelter.getName()).thenReturn("Seoul Shelter");
        when(shelter.getShelterType()).thenReturn("CIVIL_DEFENSE");
        when(shelter.getDisasterType()).thenReturn("EARTHQUAKE");
        when(shelter.getAddress()).thenReturn("Seoul");
        when(shelter.getLatitude()).thenReturn(BigDecimal.valueOf(37.56));
        when(shelter.getLongitude()).thenReturn(BigDecimal.valueOf(126.97));
        when(shelter.getCapacity()).thenReturn(120);
        when(shelter.getShelterStatus()).thenReturn("OPERATING");
        when(shelter.getUpdatedAt()).thenReturn(OffsetDateTime.now());
        return shelter;
    }

    private static DisasterAlert alert(long alertId) {
        DisasterAlert alert = mock(DisasterAlert.class);
        when(alert.getAlertId()).thenReturn(alertId);
        when(alert.getDisasterType()).thenReturn("EARTHQUAKE");
        when(alert.getRegion()).thenReturn("seoul");
        when(alert.getLevel()).thenReturn("ALERT");
        when(alert.getMessage()).thenReturn("message");
        when(alert.getIssuedAt()).thenReturn(OffsetDateTime.now());
        when(alert.getExpiredAt()).thenReturn(null);
        return alert;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
