package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
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
