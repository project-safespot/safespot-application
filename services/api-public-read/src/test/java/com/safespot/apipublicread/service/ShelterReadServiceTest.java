package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.ShelterStatusCache;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.EvacuationEntryRepository;
import com.safespot.apipublicread.repository.ShelterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterReadServiceTest {

    @Mock ShelterRepository shelterRepository;
    @Mock EvacuationEntryRepository evacuationEntryRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Spy FallbackSingleFlight fallbackSingleFlight = new FallbackSingleFlight(new SimpleMeterRegistry(), 2_000);

    @InjectMocks ShelterReadService shelterReadService;

    private Shelter shelter;

    @BeforeEach
    void setUp() {
        shelter = mock(Shelter.class);
        lenient().when(shelter.getShelterId()).thenReturn(101L);
        lenient().when(shelter.getName()).thenReturn("서울시민체육관");
        lenient().when(shelter.getShelterType()).thenReturn("민방위대피소");
        lenient().when(shelter.getDisasterType()).thenReturn("EARTHQUAKE");
        lenient().when(shelter.getAddress()).thenReturn("서울특별시 마포구");
        lenient().when(shelter.getLatitude()).thenReturn(BigDecimal.valueOf(37.5687));
        lenient().when(shelter.getLongitude()).thenReturn(BigDecimal.valueOf(126.9081));
        lenient().when(shelter.getCapacity()).thenReturn(120);
        lenient().when(shelter.getShelterStatus()).thenReturn("OPERATING");
        lenient().when(shelter.getUpdatedAt()).thenReturn(OffsetDateTime.now());
    }

    // ── /shelters/{shelterId} 상세 조회 ──────────────────────────────────────

    @Test
    void findById_cacheHit_returnsFromCache() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        ShelterStatusCacheDto cachedStatus = new ShelterStatusCacheDto(68, 52, "NORMAL", "OPERATING");
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cachedStatus, null));

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.shelterId()).isEqualTo(101L);
        assertThat(result.currentOccupancy()).isEqualTo(68);
        assertThat(result.congestionLevel()).isEqualTo("NORMAL");
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void findById_cacheMiss_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(68L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(68);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).recordDbFallbackQuery("shelter_status_repository", RedisReadCache.FallbackReason.REDIS_MISS);
        verify(cacheRegenerationPublisher).publish("shelter:status:101", CacheRegenerationReason.CACHE_MISS, "/shelters/{shelterId}");
    }

    @Test
    void findById_redisDown_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_DOWN, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(30L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(30);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.REDIS_DOWN));
        verify(cacheRegenerationPublisher).publish("shelter:status:101", CacheRegenerationReason.REDIS_DOWN, "/shelters/{shelterId}");
    }

    @Test
    void findById_suppressWindow_doesNotEmitSecondTime() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(10L);
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(false);

        shelterReadService.findById(101L);

        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findById_parseError_fallsBackWithoutRegenerationRequest() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(10L);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(10);
        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findById_notFound_throwsApiException() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> shelterReadService.findById(999L))
                .isInstanceOf(ApiException.class);
    }

    // ── /shelters/nearby MGET 경로 ───────────────────────────────────────────

    @Test
    void findNearby_usesMgetNotIndividualGets() {
        Shelter s1 = shelterAt(101L, 37.5687, 126.9081);
        Shelter s2 = shelterAt(102L, 37.5690, 126.9081);
        Shelter s3 = shelterAt(103L, 37.5695, 126.9081);

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(List.of(s1, s2, s3));

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, hit(dto),
                        102L, hit(dto),
                        103L, hit(dto)
                ));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        verify(redisReadCache).multiGetShelterStatus(
                argThat(ids -> ids.size() == 3
                        && ids.containsAll(List.of(101L, 102L, 103L)))
        );
        verify(redisReadCache, never()).get(anyString(), any(TypeReference.class));
    }

    @Test
    void findNearby_allCacheHit_noDbFallback() {
        Shelter s1 = shelterAt(101L, 37.5687, 126.9081);
        Shelter s2 = shelterAt(102L, 37.5690, 126.9081);
        Shelter s3 = shelterAt(103L, 37.5695, 126.9081);

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(List.of(s1, s2, s3));

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, hit(dto),
                        102L, hit(dto),
                        103L, hit(dto)
                ));

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(3);
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
        verify(shelterRepository, never()).findById(anyLong());
    }

    @Test
    void findNearby_mgetCacheHit_returnsFromCacheWithoutFallback() {
        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), eq("EARTHQUAKE")
        )).thenReturn(List.of(shelter));
        ShelterStatusCacheDto cachedStatus = new ShelterStatusCacheDto(20, 100, "LOW", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(cachedStatus, null, "shelter_status")));

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentOccupancy()).isEqualTo(20);
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
        verify(cacheRegenerationPublisher, never()).publishBatch(any(), any(), any(), anyString());
    }

    @Test
    void findNearby_mgetCacheMiss_fallsBackToRdsAndPublishesBatchEvent() {
        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), eq("EARTHQUAKE")
        )).thenReturn(List.of(shelter));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status")));
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(10L);
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentOccupancy()).isEqualTo(10);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).recordDbFallbackQuery("shelter_status_repository", RedisReadCache.FallbackReason.REDIS_MISS);
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(101L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/nearby"));
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findNearby_mgetCacheMiss_suppressWindowActive_doesNotPublishBatch() {
        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), eq("EARTHQUAKE")
        )).thenReturn(List.of(shelter));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status")));
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(10L);
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(false);

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        verify(cacheRegenerationPublisher, never()).publishBatch(any(), any(), any(), anyString());
    }

    @Test
    void findNearby_partialCacheMiss_onlyMissedSheltersFallsBack() {
        Shelter s1 = shelterAt(101L, 37.5687, 126.9081);
        Shelter s2 = shelterAt(102L, 37.5690, 126.9081);

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(List.of(s1, s2));

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, hit(dto),
                        102L, miss()
                ));
        when(shelterRepository.findById(102L)).thenReturn(Optional.of(s2));
        when(evacuationEntryRepository.countCurrentOccupancy(102L)).thenReturn(20L);
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(2);
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(101L);
        verify(evacuationEntryRepository).countCurrentOccupancy(102L);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(102L)), eq(CacheRegenerationReason.CACHE_MISS), anyString());
    }

    @Test
    void findNearby_mgetParseError_doesNotPublishBatchEvent() {
        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), eq("EARTHQUAKE")
        )).thenReturn(List.of(shelter));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "shelter_status")));
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(5L);

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publishBatch(any(), any(), any(), anyString());
    }

    @Test
    void findNearby_parseError_doesNotFailEntireRequest() {
        Shelter s1 = shelterAt(101L, 37.5687, 126.9081);
        Shelter s2 = shelterAt(102L, 37.5690, 126.9081);

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(List.of(s1, s2));

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, hit(dto),
                        102L, parseError()
                ));
        when(shelterRepository.findById(102L)).thenReturn(Optional.of(s2));
        when(evacuationEntryRepository.countCurrentOccupancy(102L)).thenReturn(5L);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(2);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.PARSE_ERROR));
        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
        verify(cacheRegenerationPublisher, never()).publishBatch(any(), any(), any(), anyString());
    }

    @Test
    void findNearby_orderedByDistanceAsc() {
        // DB가 거리 역순으로 반환해도 응답은 distance ASC여야 한다
        Shelter far   = shelterAt(101L, 37.5700, 126.9081); // ~144m
        Shelter mid   = shelterAt(102L, 37.5692, 126.9081); // ~55m
        Shelter close = shelterAt(103L, 37.5687, 126.9081); // ~0m

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(List.of(far, mid, close)); // 의도적으로 역순

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, hit(dto),
                        102L, hit(dto),
                        103L, hit(dto)
                ));

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).shelterId()).isEqualTo(103L); // 가장 가까운
        assertThat(result.get(1).shelterId()).isEqualTo(102L);
        assertThat(result.get(2).shelterId()).isEqualTo(101L); // 가장 먼
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).distanceM()).isLessThanOrEqualTo(result.get(i + 1).distanceM());
        }
    }

    @Test
    void findNearby_oneMissOneHit_onlyMissIdInBatchEvent() {
        Shelter shelter2 = mock(Shelter.class);
        lenient().when(shelter2.getShelterId()).thenReturn(202L);
        lenient().when(shelter2.getName()).thenReturn("서울은행나무체육관");
        lenient().when(shelter2.getShelterType()).thenReturn("민방위대피소");
        lenient().when(shelter2.getDisasterType()).thenReturn("EARTHQUAKE");
        lenient().when(shelter2.getAddress()).thenReturn("서울특별시 서대문구");
        lenient().when(shelter2.getLatitude()).thenReturn(BigDecimal.valueOf(37.5690));
        lenient().when(shelter2.getLongitude()).thenReturn(BigDecimal.valueOf(126.9085));
        lenient().when(shelter2.getCapacity()).thenReturn(200);
        lenient().when(shelter2.getShelterStatus()).thenReturn("OPERATING");
        lenient().when(shelter2.getUpdatedAt()).thenReturn(OffsetDateTime.now());

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(BigDecimal.class), eq("EARTHQUAKE")
        )).thenReturn(List.of(shelter, shelter2));

        ShelterStatusCacheDto hitDto = new ShelterStatusCacheDto(50, 150, "NORMAL", "OPERATING");
        when(redisReadCache.multiGetShelterStatus(anyList()))
                .thenReturn(Map.of(
                        101L, new RedisReadCache.CacheResult<>(hitDto, null, "shelter_status"),
                        202L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status")
                ));
        when(shelterRepository.findById(202L)).thenReturn(Optional.of(shelter2));
        when(evacuationEntryRepository.countCurrentOccupancy(202L)).thenReturn(20L);
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(true);

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE");

        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(202L)), eq(CacheRegenerationReason.CACHE_MISS), anyString());
    }

    @Test
    void findNearby_limitsTo50() {
        List<Shelter> manyShelters = new ArrayList<>();
        for (long i = 1; i <= 55; i++) {
            manyShelters.add(shelterAt(i, 37.5687, 126.9081)); // 모두 동일 위치
        }

        when(shelterRepository.findByBoundingBoxAndDisasterType(
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class), anyString()
        )).thenReturn(manyShelters);

        ShelterStatusCacheDto dto = new ShelterStatusCacheDto(10, 90, "AVAILABLE", "OPERATING");
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> statusMap = new HashMap<>();
        for (long i = 1; i <= 55; i++) { // 55개 전체를 MGET에 전달, limit은 이후 적용
            statusMap.put(i, hit(dto));
        }
        when(redisReadCache.multiGetShelterStatus(anyList())).thenReturn(statusMap);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 10_000, "EARTHQUAKE");

        assertThat(result).hasSize(50);
        verify(redisReadCache).multiGetShelterStatus(argThat(ids -> ids.size() == 55));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Shelter shelterAt(long id, double lat, double lng) {
        Shelter s = mock(Shelter.class);
        lenient().when(s.getShelterId()).thenReturn(id);
        lenient().when(s.getName()).thenReturn("대피소-" + id);
        lenient().when(s.getShelterType()).thenReturn("민방위대피소");
        lenient().when(s.getDisasterType()).thenReturn("EARTHQUAKE");
        lenient().when(s.getAddress()).thenReturn("서울특별시");
        lenient().when(s.getLatitude()).thenReturn(BigDecimal.valueOf(lat));
        lenient().when(s.getLongitude()).thenReturn(BigDecimal.valueOf(lng));
        lenient().when(s.getCapacity()).thenReturn(100);
        lenient().when(s.getShelterStatus()).thenReturn("OPERATING");
        lenient().when(s.getUpdatedAt()).thenReturn(OffsetDateTime.now());
        return s;
    }

    private RedisReadCache.CacheResult<ShelterStatusCacheDto> hit(ShelterStatusCacheDto dto) {
        return new RedisReadCache.CacheResult<>(dto, null, "shelter_status");
    }

    private RedisReadCache.CacheResult<ShelterStatusCacheDto> miss() {
        return new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status");
    }

    private RedisReadCache.CacheResult<ShelterStatusCacheDto> parseError() {
        return new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "shelter_status");
    }
}
