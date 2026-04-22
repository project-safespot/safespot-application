package com.safespot.asyncworker.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.service.shelter.ShelterStatusValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheWriterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisCacheWriter cacheWriter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        cacheWriter = new RedisCacheWriter(redisTemplate, new ObjectMapper());
    }

    @Test
    void setShelterStatus_정상_SET() {
        ShelterStatusValue value = new ShelterStatusValue(5, 45, "LOW", "OPEN");

        cacheWriter.setShelterStatus(101L, value);

        verify(valueOps).set(
            eq(RedisKeyConstants.shelterStatus(101L)),
            anyString(),
            eq(RedisTtlConstants.SHELTER_STATUS)
        );
    }

    @Test
    void setShelterStatus_Redis_IO_실패시_RedisCacheException() {
        doThrow(new RuntimeException("Connection refused"))
            .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() ->
            cacheWriter.setShelterStatus(101L, new ShelterStatusValue(5, 45, "LOW", "OPEN"))
        ).isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("Redis SET failed");
    }

    @Test
    void setWeather_직렬화_실패시_EventProcessingException() {
        // ObjectMapper가 직렬화할 수 없는 값을 강제 주입할 수 없어서
        // cacheWriter를 broken ObjectMapper로 직접 생성
        ObjectMapper brokenMapper = mock(ObjectMapper.class, invocation -> {
            throw new com.fasterxml.jackson.core.JsonProcessingException("serialization error") {};
        });
        RedisCacheWriter writerWithBrokenMapper = new RedisCacheWriter(redisTemplate, brokenMapper);

        assertThatThrownBy(() ->
            writerWithBrokenMapper.setWeather(new WeatherCacheValue(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00"))
        ).isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("Redis SET serialization failed");
    }

    @Test
    void deleteDisasterActive_Redis_실패시_RedisCacheException() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() ->
            cacheWriter.deleteDisasterActive("서울")
        ).isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("Redis DEL failed");
    }
}
