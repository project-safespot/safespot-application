package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
    void nearby가_shelterRepository_findByBoundingBoxAndDisasterType를_호출하지_않는다() {
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
    void nearby가_map_item과_status를_읽어_overlay한다() {
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
        assertThat(result.get(0).capacityTotal()).isEqualTo(100);
        verify(redisReadCache).multiGetShelterMapItems(List.of(101L));
        verify(redisReadCache).multiGetShelterStatus(List.of(101L));
    }

    @Test
    void nearby_disasterType_shelterType_미지정이면_all_all_dimension을_사용한다() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:all:all"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_disasterType만_있으면_disaster_all_dimension을_사용한다() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, "FLOOD", null, 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:FLOOD:all"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_shelterType만_있으면_all_shelter_dimension을_사용한다() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(List.of(), null, "shelter_geo_index"));

        shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, "WIDE", 50);

        verify(redisReadCache).geoSearchShelterIds(eq("shelter:geo:seoul:all:WIDE"), anyDouble(), anyDouble(), anyDouble(), eq(50));
    }

    @Test
    void nearby_map_item_partial_miss면_hit만_반환하고_batch_regeneration을_요청한다() {
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
    void nearby_geo_miss면_SHELTER_GEO_INDEX_regeneration을_요청한다() {
        when(redisReadCache.geoSearchShelterIds(anyString(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "shelter_geo_index"));
        when(suppressWindowService.tryPublish("shelter:geo:seoul:all:all")).thenReturn(true);

        List<ShelterNearbyItem> result = shelterReadService.findNearby(37.5687, 126.9081, 1_000, null, null, 50);

        assertThat(result).isEmpty();
        verify(cacheRegenerationPublisher).publishTarget("SHELTER_GEO_INDEX", CacheRegenerationReason.CACHE_MISS, "/shelters/nearby");
        verify(redisReadCache, never()).multiGetShelterMapItems(anyList());
    }

    @Test
    void nearby_status_miss면_default_status를_사용한다() {
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
        assertThat(result.get(0).congestionLevel()).isNull();
        assertThat(result.get(0).shelterStatus()).isNull();
        verify(cacheRegenerationPublisher).publishBatch(
                eq("SHELTER_STATUS"), eq(List.of(101L)), eq(CacheRegenerationReason.CACHE_MISS), eq("/shelters/nearby"));
    }

    @Test
    void nearby_limit이_적용된다() {
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
    void map_tiles_정상응답() {
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

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(13, List.of("7285:3172", "7285:3173"), "FLOOD", "DESIGNATED");

        assertThat(response.tiles()).hasSize(2);
        assertThat(response.tiles().get(0).items()).hasSize(2);
        assertThat(response.tiles().get(1).items()).hasSize(1);
        verify(redisReadCache).multiGetShelterMapTiles(List.of(
                "shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED",
                "shelter:map:tile:13:7285:3173:FLOOD:DESIGNATED"
        ));
        verify(redisReadCache).multiGetShelterMapItems(List.of(101L, 202L));
        verify(shelterRepository, never()).findByBoundingBoxAndDisasterType(any(), any(), any(), any(), any());
    }

    @Test
    void map_tiles_unsupported_zoom이면_validation_error() {
        assertThatThrownBy(() -> shelterReadService.findMapTiles(10, List.of("7285:3172"), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void map_tiles_partial_tile_miss면_regeneration을_요청한다() {
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
        return new RedisReadCache.CacheResult<>(new ShelterStatusCacheDto(occupancy, available, "AVAILABLE", "OPERATING"), null, "shelter_status");
    }

    private ShelterMapItemCacheDto mapItem(long id) {
        return new ShelterMapItemCacheDto(
                1,
                id,
                "대피소-" + id,
                "DESIGNATED",
                "FLOOD",
                "서울특별시",
                37.5687,
                126.9081,
                "2026-05-15T10:00:00Z"
        );
    }
}
