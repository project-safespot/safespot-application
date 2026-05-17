package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.DistributedFallbackGuard;
import com.safespot.apipublicread.cache.FallbackControlProperties;
import com.safespot.apipublicread.cache.PublicReadMetricRecorder;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterMapTilesResponse;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    @Mock DistributedFallbackGuard distributedFallbackGuard;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Spy FallbackSingleFlight fallbackSingleFlight = new FallbackSingleFlight(new SimpleMeterRegistry(), 2_000);
    @Spy PublicReadMetricRecorder metricRecorder = new PublicReadMetricRecorder(new SimpleMeterRegistry());
    @Spy FallbackControlProperties fallbackControlProperties = new FallbackControlProperties();

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
        lenient().when(distributedFallbackGuard.tryAcquire(anyString(), anyString(), anyString(), any()))
                .thenReturn(DistributedFallbackGuard.Decision.LEADER);
    }

    @Test
    void findById_cacheHit_returnsFromCache() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        ShelterStatusCacheDto cachedStatus = new ShelterStatusCacheDto(68, 52, "NORMAL", "OPERATING");
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cachedStatus, null));

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.shelterId()).isEqualTo(101L);
        assertThat(result.currentOccupancy()).isEqualTo(68);
        verify(evacuationEntryRepository, never()).countCurrentOccupancy(anyLong());
    }

    @Test
    void findById_cacheMiss_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(68L);
        when(suppressWindowService.tryPublish(eq("shelter:status:101"), any(Duration.class))).thenReturn(true);

        ShelterDetailDto result = shelterReadService.findById(101L);

        assertThat(result.currentOccupancy()).isEqualTo(68);
        verify(redisReadCache).recordFallback(eq("shelter_status"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).recordDbFallbackQuery("shelter_status", "shelter_status_repository", RedisReadCache.FallbackReason.REDIS_MISS, "leader");
        verify(cacheRegenerationPublisher).publish("shelter:status:101", CacheRegenerationReason.CACHE_MISS, "/shelters/{shelterId}");
    }

    @Test
    void findById_redisDown_fallsBackToRdsAndEmitsEvent() {
        when(shelterRepository.findById(101L)).thenReturn(Optional.of(shelter));
        when(redisReadCache.get(eq("shelter:status:101"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_DOWN, "shelter_status"));
        when(evacuationEntryRepository.countCurrentOccupancy(101L)).thenReturn(30L);
        when(suppressWindowService.tryPublish(eq("shelter:status:101"), any(Duration.class))).thenReturn(true);

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
        when(suppressWindowService.tryPublish(eq("shelter:status:101"), any(Duration.class))).thenReturn(false);

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
        verify(suppressWindowService, never()).tryPublish(anyString(), any(Duration.class));
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findById_notFound_throwsApiException() {
        when(shelterRepository.findById(999L)).thenReturn(Optional.empty());

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
        when(suppressWindowService.tryPublish(eq("shelter:map:item:batch"), any(Duration.class))).thenReturn(true);

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
        when(suppressWindowService.tryPublish(eq("shelter:geo:seoul:all:all"), any(Duration.class))).thenReturn(true);

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
        when(suppressWindowService.tryPublish(eq("shelter:status:batch"), any(Duration.class))).thenReturn(true);

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
        when(suppressWindowService.tryPublish(eq("shelter:status:batch"), any(Duration.class))).thenReturn(true);

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
        when(suppressWindowService.tryPublish(eq("shelter:map:tile:13:7285:3172:all:all"), any(Duration.class))).thenReturn(true);

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(13, List.of("7285:3172"), null, null);

        assertThat(response.tiles()).hasSize(1);
        verify(cacheRegenerationPublisher).publish("shelter:map:tile:13:7285:3172:all:all", CacheRegenerationReason.CACHE_MISS, "/shelters/map/tiles");
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
}
