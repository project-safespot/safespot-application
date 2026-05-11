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
    private final RedisReadCache redisReadCache =
            new RedisReadCache(redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());

    @Test
    void missingKey_isCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("shelter:status:101")).thenReturn(null);

        RedisReadCache.CacheResult<ShelterStatusCacheDto> result =
                redisReadCache.get("shelter:status:101", new TypeReference<>() {});

        assertThat(result.isMiss()).isTrue();
        assertThat(result.resultLabel()).isEqualTo("miss");
    }

    @Test
    void presentKeyWithInvalidJson_isParseError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("shelter:status:101")).thenReturn("{not-json");

        RedisReadCache.CacheResult<ShelterStatusCacheDto> result =
                redisReadCache.get("shelter:status:101", new TypeReference<>() {});

        assertThat(result.isParseError()).isTrue();
        assertThat(result.resultLabel()).isEqualTo("parse_error");
    }
}
