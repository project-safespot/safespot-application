package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterMapTilesResponse;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.cache.ShelterDetailCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        lenient().when(shelter.getName()).thenReturn("서울서부체육관");
        lenient().when(shelter.getShelterType()).thenReturn("DESIGNATED");
        lenient().when(shelter.getDisasterType()).thenReturn("EARTHQUAKE");
        lenient().when(shelter.getAddress()).thenReturn("서울특별시 마포구");
        lenient().when(shelter.getLatitude()).thenReturn(BigDecimal.valueOf(37.5687));
        lenient().when(shelter.getLongitude()).thenReturn(BigDecimal.valueOf(126.9081));
        lenient().when(shelter.getCapacity()).thenReturn(120);
        lenient().when(shelter.getShelterStatus()).thenReturn("OPERATING");
        lenient().when(shelter.getUpdatedAt()).thenReturn(OffsetDateTime.now());
    }

    @Test
    void findById_detailCacheHit_doesNotCallShelterRepository() {
        when(redisReadCache.getShelterDetail(101L)).thenReturn(new RedisReadCache.CacheResult<>(detailCache(101L), null, "shelter_detail"));
        ShelterStatusCacheDto cachedStatus = new ShelterStatusCacheDto(68, 52, "NORMAL", "OPERATING");
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cachedStatus, null));

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.shelterId()).isEqualTo(101L);
        assertThat(result.currentOccupancy()).isEqualTo(68);
        verify(shelterRepository, never()).findById(101L);
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void findById_detailCacheMiss_publishesShelterDetailRegenerationOnceWithinSuppressWindow() {
        when(redisReadCache.getShelterDetail(101L))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_detail"));
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(new ShelterStatusCacheDto(68, 52, "NORMAL", "OPERATING"), null, "shelter_status"));
        when(suppressWindowService.tryPublish("shelter:detail:101")).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.shelterId()).isEqualTo(101L);
        verify(redisReadCache).recordFallback(eq("shelter_detail"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_DETAIL"), eq(List.of(101L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/{shelterId}"));
    }

    @Test
    void findById_detailCacheMiss_concurrentRequestsPerformOneShelterRepositoryFallback() throws Exception {
        when(redisReadCache.getShelterDetail(101L))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_detail"));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(new ShelterStatusCacheDto(68, 52, "NORMAL", "OPERATING"), null, "shelter_status"));
        when(suppressWindowService.tryPublish("shelter:detail:101")).thenReturn(false);
        AtomicInteger repositoryCalls = new AtomicInteger();
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(shelterRepository.findById(101L)).thenAnswer(invocation -> {
            repositoryCalls.incrementAndGet();
            firstCallStarted.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(shelter);
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> shelterReadService.findById(101L));
            assertThat(firstCallStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> shelterReadService.findById(101L));
            release.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS).shelterId()).isEqualTo(101L);
            assertThat(second.get(1, TimeUnit.SECONDS).shelterId()).isEqualTo(101L);
            assertThat(repositoryCalls).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void findById_statusCacheMiss_stillUsesExistingStatusSingleFlight() throws Exception {
        when(redisReadCache.getShelterDetail(101L)).thenReturn(new RedisReadCache.CacheResult<>(detailCache(101L), null, "shelter_detail"));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status"));
        AtomicInteger occupancyCalls = new AtomicInteger();
        CountDownLatch firstCountStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenAnswer(invocation -> {
            occupancyCalls.incrementAndGet();
            firstCountStarted.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return 68L;
        });
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(suppressWindowService.tryPublish("shelter:status:101")).thenReturn(true);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> shelterReadService.findById(101L));
            assertThat(firstCountStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> shelterReadService.findById(101L));
            release.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS).currentOccupancy()).isEqualTo(68);
            assertThat(second.get(1, TimeUnit.SECONDS).currentOccupancy()).isEqualTo(68);
            assertThat(occupancyCalls).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void findById_parseError_fallsBackWithoutRegenerationRequest() {
        when(redisReadCache.getShelterDetail(101L))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "shelter_detail"));
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(new ShelterStatusCacheDto(10, 110, "AVAILABLE", "OPERATING"), null, "shelter_status"));

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(10);
        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publishBatch(anyString(), anyList(), any(), anyString());
    }

    @Test
    void findById_notFound_throwsApiException() {
        when(redisReadCache.getShelterDetail(999L))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_detail"));
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());
        when(redisReadCache.get(eq("shelter:status:999"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(new ShelterStatusCacheDto(0, 0, null, null), null, "shelter_status"));
        when(suppressWindowService.tryPublish("shelter:detail:999")).thenReturn(false);

        assertThatThrownBy(() -> shelterReadService.findById(999L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nearby_usesGeoAndOverlayWithoutBoundingBoxRepository() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(new RedisReadCache.GeoSearchHit(101L, 120d)), null, "shelter_geo_index"));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item")));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, hitStatus(10, 90)));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE", "DESIGNATED", 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:EARTHQUAKE:DESIGNATED"), eq(126.9081), eq(37.5687), eq(1000d), eq(50));
        verify(shelterRepository, never()).findByBoundingBoxAndDisasterType(any(), any(), any(), any(), any());
    }

    @Test
    void nearby_readsMapItemAndStatusAndOverlaysThem() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(new RedisReadCache.GeoSearchHit(101L, 120d)), null, "shelter_geo_index"));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item")));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, hitStatus(12, 88)));

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, "EARTHQUAKE", null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shelterId()).isEqualTo(101L);
        assertThat(result.get(0).currentOccupancy()).isEqualTo(12);
        assertThat(result.get(0).availableCapacity()).isEqualTo(88);
        assertThat(result.get(0).capacityTotal()).isEqualTo(120);
        verify(redisReadCache).multiGetShelterMapItems(List.of(101L));
        verify(redisReadCache).multiGetShelterStatus(List.of(101L));
    }

    @Test
    void nearby_usesAllAllDimensionWhenFiltersMissing() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:all:all"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_usesDisasterAllDimensionWhenShelterTypeMissing() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "FLOOD", null, 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:FLOOD:all"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_usesAllShelterDimensionWhenDisasterTypeMissing() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, "WIDE", 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:all:WIDE"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_mapItemPartialMiss_returnsHitsOnlyAndPublishesBatchRegeneration() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(
                        List.of(new RedisReadCache.GeoSearchHit(101L, 120d), new RedisReadCache.GeoSearchHit(202L, 130d)),
                        null,
                        "shelter_geo_index"));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L, 202L)))
                .thenReturn(Map.of(
                        101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item"),
                        202L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_map_item")
                ));
        when(redisReadCache.multiGetShelterStatus(List.of(101L, 202L)))
                .thenReturn(Map.of(
                        101L, hitStatus(12, 88),
                        202L, hitStatus(20, 80)
                ));
        when(suppressWindowService.tryPublish("shelter:map:item:batch")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shelterId()).isEqualTo(101L);
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_MAP_ITEMS"), eq(List.of(202L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/nearby"));
    }

    @Test
    void nearby_geoMiss_publishesGeoIndexRegeneration() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_geo_index"));
        when(suppressWindowService.tryPublish("shelter:geo:seoul:all:all")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        assertThat(result).isEmpty();
        verify(cacheRegenerationPublisher).publishTarget("SHELTER_GEO_INDEX", CacheRegenerationReason.CACHE_MISS, "/shelters/nearby");
        verify(redisReadCache, never()).multiGetShelterMapItems(anyList());
    }

    @Test
    void nearby_statusMiss_usesDefaultStatusAndPublishesBatchRegeneration() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(new RedisReadCache.GeoSearchHit(101L, 120d)), null, "shelter_geo_index"));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item")));
        when(redisReadCache.multiGetShelterStatus(List.of(101L)))
                .thenReturn(Map.of(101L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status")));
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentOccupancy()).isZero();
        assertThat(result.get(0).availableCapacity()).isZero();
        assertThat(result.get(0).capacityTotal()).isEqualTo(120);
        assertThat(result.get(0).congestionLevel()).isNull();
        assertThat(result.get(0).shelterStatus()).isNull();
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(101L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/nearby"));
    }

    @Test
    void nearby_limitIsAppliedAfterOverlay() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(
                        List.of(
                                new RedisReadCache.GeoSearchHit(101L, 100d),
                                new RedisReadCache.GeoSearchHit(102L, 200d)
                        ),
                        null,
                        "shelter_geo_index"));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L, 102L)))
                .thenReturn(Map.of(
                        101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item"),
                        102L, new RedisReadCache.CacheResult<>(mapItem(102L), null, "shelter_map_item")
                ));
        when(redisReadCache.multiGetShelterStatus(List.of(101L, 102L)))
                .thenReturn(Map.of(
                        101L, hitStatus(10, 90),
                        102L, hitStatus(20, 80)
                ));

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).shelterId()).isEqualTo(101L);
    }

    @Test
    void mapTiles_readsStatusMgetAndMergesOverlayFields() {
        when(redisReadCache.multiGetShelterMapTiles(List.of(
                "shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED",
                "shelter:map:tile:13:7285:3173:FLOOD:DESIGNATED"
        ))).thenReturn(Map.of(
                "shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED", new RedisReadCache.CacheResult<>(List.of(101L, 202L), null, "shelter_map_tile"),
                "shelter:map:tile:13:7285:3173:FLOOD:DESIGNATED", new RedisReadCache.CacheResult<>(List.of(202L), null, "shelter_map_tile")
        ));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L, 202L))).thenReturn(Map.of(
                101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item"),
                202L, new RedisReadCache.CacheResult<>(mapItem(202L), null, "shelter_map_item")
        ));
        when(redisReadCache.multiGetShelterStatus(List.of(101L, 202L))).thenReturn(Map.of(
                101L, hitStatus(12, 108),
                202L, hitStatus(20, 100)
        ));

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(13, List.of("7285:3172", "7285:3173"), "FLOOD", "DESIGNATED");

        assertThat(response.tiles()).hasSize(2);
        assertThat(response.tiles().get(0).items()).hasSize(2);
        assertThat(response.tiles().get(1).items()).hasSize(1);
        ShelterMapItemCacheDto item = response.tiles().get(0).items().get(0);
        assertThat(item.currentOccupancy()).isEqualTo(12);
        assertThat(item.availableCapacity()).isEqualTo(108);
        assertThat(item.congestionLevel()).isEqualTo("AVAILABLE");
        assertThat(item.shelterStatus()).isEqualTo("OPERATING");
        verify(redisReadCache).multiGetShelterMapTiles(List.of(
                "shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED",
                "shelter:map:tile:13:7285:3173:FLOOD:DESIGNATED"
        ));
        verify(redisReadCache).multiGetShelterMapItems(List.of(101L, 202L));
        verify(redisReadCache).multiGetShelterStatus(List.of(101L, 202L));
        verify(shelterRepository, never()).findByBoundingBoxAndDisasterType(any(), any(), any(), any(), any());
        verify(shelterRepository, never()).findById(anyLong());
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void mapTiles_statusMiss_degradesWithoutFailureAndKeepsMapCapacity() {
        when(redisReadCache.multiGetShelterMapTiles(List.of("shelter:map:tile:13:7285:3172:all:all")))
                .thenReturn(Map.of(
                        "shelter:map:tile:13:7285:3172:all:all",
                        new RedisReadCache.CacheResult<>(List.of(101L), null, "shelter_map_tile")
                ));
        when(redisReadCache.multiGetShelterMapItems(List.of(101L))).thenReturn(Map.of(
                101L, new RedisReadCache.CacheResult<>(mapItem(101L), null, "shelter_map_item")
        ));
        when(redisReadCache.multiGetShelterStatus(List.of(101L))).thenReturn(Map.of(
                101L, new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status")
        ));
        when(suppressWindowService.tryPublish("shelter:status:batch")).thenReturn(true);

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(13, List.of("7285:3172"), null, null);

        assertThat(response.degraded()).isTrue();
        assertThat(response.tiles()).hasSize(1);
        assertThat(response.tiles().get(0).items()).hasSize(1);
        ShelterMapItemCacheDto item = response.tiles().get(0).items().get(0);
        assertThat(item.capacityTotal()).isEqualTo(120);
        assertThat(item.currentOccupancy()).isZero();
        assertThat(item.availableCapacity()).isZero();
        assertThat(item.congestionLevel()).isNull();
        assertThat(item.shelterStatus()).isNull();
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(101L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/map/tiles"));
        verify(shelterRepository, never()).findById(anyLong());
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void mapTiles_unsupportedZoom_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(10, List.of("7285:3172"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_blankToken_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("7285:3172", " "), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_negativeX_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("-1:3172"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_negativeY_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("7285:-1"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_xOverflow_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("8192:0"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_yOverflow_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("0:8192"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_nonNumericCoordinate_throwsValidationError() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("abc:1"), null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> shelterReadService.findMapTiles(13, List.of("1:abc"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void mapTiles_partialTileMiss_publishesTileRegeneration() {
        when(redisReadCache.multiGetShelterMapTiles(List.of("shelter:map:tile:13:7285:3172:all:all")))
                .thenReturn(Map.of(
                        "shelter:map:tile:13:7285:3172:all:all",
                        new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_map_tile")
                ));
        when(suppressWindowService.tryPublish("shelter:map:tile:batch:13:all:all")).thenReturn(true);

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(13, List.of("7285:3172"), null, null);

        assertThat(response.tiles()).hasSize(1);
        verify(cacheRegenerationPublisher).publishTarget("SHELTER_MAP_TILES", CacheRegenerationReason.CACHE_MISS, "/shelters/map/tiles");
    }

    private RedisReadCache.CacheResult<ShelterStatusCacheDto> hitStatus(int occupancy, int available) {
        return new RedisReadCache.CacheResult<>(
                new ShelterStatusCacheDto(occupancy, available, "AVAILABLE", "OPERATING"),
                null,
                "shelter_status"
        );
    }

    private ShelterMapItemCacheDto mapItem(long id) {
        return new ShelterMapItemCacheDto(
                1,
                id,
                "대피소-" + id,
                "DESIGNATED",
                "FLOOD",
                "서울특별시",
                120,
                null,
                null,
                null,
                null,
                37.5687,
                126.9081,
                "2026-05-15T10:00:00Z"
        );
    }

    private ShelterDetailCacheDto detailCache(long id) {
        return new ShelterDetailCacheDto(
                1,
                id,
                "대피소-" + id,
                "DESIGNATED",
                "FLOOD",
                "서울시",
                37.5687,
                126.9081,
                120,
                "manager",
                "010-1234-5678",
                "note",
                "2026-05-15T10:00:00Z"
        );
    }
}
