package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisReadCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
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
        assertThat(result.resultLabel()).isEqualTo("miss");
        assertThat(result.cache()).isEqualTo("shelter_status");
        assertThat(meterRegistry.find("safespot.redis.read")
                .tag("cache", "shelter_status")
                .tag("result", "success")
                .timer()).isNotNull();
    }

    @Test
    void presentKeyWithInvalidJson_isParseError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("shelter:status:101")).thenReturn("{not-json");

        RedisReadCache.CacheResult<ShelterStatusCacheDto> result =
                redisReadCache.get("shelter:status:101", new TypeReference<>() {});

        assertThat(result.isParseError()).isTrue();
        assertThat(result.resultLabel()).isEqualTo("parse_error");
        assertThat(result.cache()).isEqualTo("shelter_status");
        assertThat(meterRegistry.find("safespot.redis.read")
                .tag("cache", "shelter_status")
                .tag("result", "failure")
                .timer()).isNotNull();
    }

    @Test
    void getAll_allHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String json1 = "{\"currentOccupancy\":10,\"availableCapacity\":90,\"congestionLevel\":\"AVAILABLE\",\"shelterStatus\":\"OPERATING\"}";
        String json2 = "{\"currentOccupancy\":20,\"availableCapacity\":80,\"congestionLevel\":\"NORMAL\",\"shelterStatus\":\"OPERATING\"}";
        when(valueOperations.multiGet(List.of("shelter:status:1", "shelter:status:2")))
                .thenReturn(List.of(json1, json2));

        Map<String, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.getAll(List.of("shelter:status:1", "shelter:status:2"), new TypeReference<>() {});

        assertThat(result.get("shelter:status:1").isHit()).isTrue();
        assertThat(result.get("shelter:status:1").value().currentOccupancy()).isEqualTo(10);
        assertThat(result.get("shelter:status:2").isHit()).isTrue();
        assertThat(result.get("shelter:status:2").value().currentOccupancy()).isEqualTo(20);
    }

    @Test
    void getAll_partialMiss_nullValueIsMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String json1 = "{\"currentOccupancy\":10,\"availableCapacity\":90,\"congestionLevel\":\"AVAILABLE\",\"shelterStatus\":\"OPERATING\"}";
        when(valueOperations.multiGet(List.of("shelter:status:1", "shelter:status:2")))
                .thenReturn(Arrays.asList(json1, null));

        Map<String, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.getAll(List.of("shelter:status:1", "shelter:status:2"), new TypeReference<>() {});

        assertThat(result.get("shelter:status:1").isHit()).isTrue();
        assertThat(result.get("shelter:status:2").isMiss()).isTrue();
    }

    @Test
    void getAll_parseError_onlyAffectedKeyFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String validJson = "{\"currentOccupancy\":10,\"availableCapacity\":90,\"congestionLevel\":\"AVAILABLE\",\"shelterStatus\":\"OPERATING\"}";
        when(valueOperations.multiGet(List.of("shelter:status:1", "shelter:status:2")))
                .thenReturn(List.of(validJson, "{not-json"));

        Map<String, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.getAll(List.of("shelter:status:1", "shelter:status:2"), new TypeReference<>() {});

        assertThat(result.get("shelter:status:1").isHit()).isTrue();
        assertThat(result.get("shelter:status:2").isParseError()).isTrue();
        assertThat(result.get("shelter:status:2").resultLabel()).isEqualTo("parse_error");
    }

    @Test
    void getAll_connectionFailure_allDown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        Map<String, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.getAll(List.of("shelter:status:1", "shelter:status:2"), new TypeReference<>() {});

        assertThat(result.get("shelter:status:1").isDown()).isTrue();
        assertThat(result.get("shelter:status:2").isDown()).isTrue();
    }

    @Test
    void getAll_emptyList_returnsEmptyMap() {
        Map<String, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.getAll(List.of(), new TypeReference<>() {});

        assertThat(result).isEmpty();
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
        assertThat(result.get(1L).value().currentOccupancy()).isEqualTo(30);
        assertThat(result.get(2L).isMiss()).isTrue();
        assertThat(result.get(2L).fallbackReason()).isEqualTo(RedisReadCache.FallbackReason.REDIS_MISS);
    }

    @Test
    void multiGetShelterStatus_allMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("shelter:status:10", "shelter:status:20")))
                .thenReturn(Arrays.asList(null, null));

        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.multiGetShelterStatus(List.of(10L, 20L));

        assertThat(result.get(10L).isMiss()).isTrue();
        assertThat(result.get(20L).isMiss()).isTrue();
    }

    @Test
    void multiGetShelterStatus_parseError_isDistinctFromMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("shelter:status:5")))
                .thenReturn(List.of("{bad-json"));

        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.multiGetShelterStatus(List.of(5L));

        assertThat(result.get(5L).isParseError()).isTrue();
        assertThat(result.get(5L).isMiss()).isFalse();
        assertThat(meterRegistry.find("safespot.cache.shelter_status.mget.parse_error").counter())
                .isNotNull();
    }

    @Test
    void multiGetShelterStatus_emptyInput_returnsEmptyMap() {
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.multiGetShelterStatus(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void multiGetShelterStatus_deduplicatesIds() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String hitJson = new ObjectMapper().writeValueAsString(
                new ShelterStatusCacheDto(10, 90, "LOW", "OPERATING"));
        when(valueOperations.multiGet(List.of("shelter:status:7")))
                .thenReturn(List.of(hitJson));

        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> result =
                redisReadCache.multiGetShelterStatus(List.of(7L, 7L));

        assertThat(result).hasSize(1);
        assertThat(result.get(7L).isHit()).isTrue();
    }

    @Test
    void customMetrics_useLowCardinalityTags() {
        redisReadCache.recordCacheRequest("disaster_detail", "miss");
        redisReadCache.recordFallback("disaster_detail", RedisReadCache.FallbackReason.REDIS_MISS);
        redisReadCache.recordDbFallbackQuery("disaster_alert_repository", RedisReadCache.FallbackReason.REDIS_MISS);
        redisReadCache.recordDbFallbackLatency("disaster_alert_repository", "success", 10);

        assertThat(meterRegistry.find("safespot.cache.requests")
                .tag("cache", "disaster_detail")
                .tag("result", "miss")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("safespot.cache.fallback")
                .tag("cache", "disaster_detail")
                .tag("reason", "redis_miss")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("safespot.db.fallback.queries")
                .tag("repository", "disaster_alert_repository")
                .tag("reason", "cache_miss")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("safespot.db.fallback")
                .tag("repository", "disaster_alert_repository")
                .tag("result", "success")
                .timer()).isNotNull();
    }
}
