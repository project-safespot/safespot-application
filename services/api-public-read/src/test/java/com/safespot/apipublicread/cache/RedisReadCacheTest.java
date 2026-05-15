package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.domain.geo.GeoReference;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisReadCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RedisReadCache redisReadCache =
            new RedisReadCache(redisTemplate, new ObjectMapper(), meterRegistry);

    @Test
    void missingKey_isCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("shelter:status:101")).thenReturn(null);

        RedisReadCache.CacheResult<ShelterStatusCacheDto> result =
                redisReadCache.get("shelter:status:101", new TypeReference<>() {});

        assertThat(result.isMiss()).isTrue();
    }

    @Test
    void presentKeyWithInvalidJson_isParseError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("shelter:status:101")).thenReturn("{not-json");

        RedisReadCache.CacheResult<ShelterStatusCacheDto> result =
                redisReadCache.get("shelter:status:101", new TypeReference<>() {});

        assertThat(result.isParseError()).isTrue();
    }

    @Test
    void multiGetShelterStatus_partialHit() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String hitJson = new ObjectMapper().writeValueAsString(
                new ShelterStatusCacheDto(30, 70, "LOW", "OPERATING"));
        when(valueOperations.multiGet(List.of("shelter:status:1", "shelter:status:2")))
                .thenReturn(Arrays.asList(hitJson, null));

        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.multiGetShelterStatus(List.of(1L, 2L));

        assertThat(result.get(1L).isHit()).isTrue();
        assertThat(result.get(2L).isMiss()).isTrue();
    }

    @Test
    void geoSearchShelterIds_returnsDistanceSortedHits() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(any(String.class), any(GeoReference.class), any(Distance.class), any(GeoSearchCommandArgs.class))).thenReturn(new GeoResults<>(List.of(
                new GeoResult<>(new GeoLocation<>("101", null), new Distance(0.12, Metrics.KILOMETERS)),
                new GeoResult<>(new GeoLocation<>("202", null), new Distance(0.15, Metrics.KILOMETERS))
        )));

        RedisReadCache.CacheResult<List<RedisReadCache.GeoSearchHit>> result =
                redisReadCache.geoSearchShelterIds("shelter:geo:seoul:all:all", 126.97, 37.56, 1000, 50);

        assertThat(result.isHit()).isTrue();
        assertThat(result.value()).extracting(RedisReadCache.GeoSearchHit::shelterId).containsExactly(101L, 202L);
    }

    @Test
    void geoSearchShelterIds_emptyResultAndMissingKey_isMiss() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(geoOperations.search(any(String.class), any(GeoReference.class), any(Distance.class), any(GeoSearchCommandArgs.class))).thenReturn(new GeoResults<>(List.of()));
        when(redisTemplate.hasKey("shelter:geo:seoul:all:all")).thenReturn(false);

        RedisReadCache.CacheResult<List<RedisReadCache.GeoSearchHit>> result =
                redisReadCache.geoSearchShelterIds("shelter:geo:seoul:all:all", 126.97, 37.56, 1000, 50);

        assertThat(result.isMiss()).isTrue();
    }

    @Test
    void multiGetShelterMapItems_partialMiss() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String hitJson = new ObjectMapper().writeValueAsString(
                new ShelterMapItemCacheDto(1, 1L, "대피소-1", "DESIGNATED", "FLOOD", "서울", 120, null, null, null, null, 37.56, 126.97, "2026-05-15T10:00:00Z"));
        when(valueOperations.multiGet(List.of("shelter:map:item:1", "shelter:map:item:2")))
                .thenReturn(Arrays.asList(hitJson, null));

        Map<Long, RedisReadCache.CacheResult<ShelterMapItemCacheDto>> result =
                redisReadCache.multiGetShelterMapItems(List.of(1L, 2L));

        assertThat(result.get(1L).isHit()).isTrue();
        assertThat(result.get(2L).isMiss()).isTrue();
    }

    @Test
    void multiGetShelterMapTiles_partialMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("tile:1", "tile:2")))
                .thenReturn(Arrays.asList("[1,2]", null));

        Map<String, RedisReadCache.CacheResult<List<Long>>> result =
                redisReadCache.multiGetShelterMapTiles(List.of("tile:1", "tile:2"));

        assertThat(result.get("tile:1").isHit()).isTrue();
        assertThat(result.get("tile:1").value()).containsExactly(1L, 2L);
        assertThat(result.get("tile:2").isMiss()).isTrue();
    }

    @Test
    void multiGetShelterMapTiles_connectionFailure_allDown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        Map<String, RedisReadCache.CacheResult<List<Long>>> result =
                redisReadCache.multiGetShelterMapTiles(List.of("tile:1", "tile:2"));

        assertThat(result.get("tile:1").isDown()).isTrue();
        assertThat(result.get("tile:2").isDown()).isTrue();
    }
}
