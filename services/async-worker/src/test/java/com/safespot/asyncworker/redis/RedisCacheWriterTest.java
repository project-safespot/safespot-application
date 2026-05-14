package com.safespot.asyncworker.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.service.shelter.ShelterStatusValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheWriterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisCacheWriter cacheWriter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        cacheWriter = new RedisCacheWriter(redisTemplate, new ObjectMapper(), workerMetrics);
    }

    @Test
    void setShelterStatus_withAddedJitterTtl_3600to3720s() {
        cacheWriter.setShelterStatus(101L, new ShelterStatusValue(5, 45, "LOW", "OPEN"));

        verify(valueOps).set(
            eq(RedisKeyConstants.shelterStatus(101L)),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.SHELTER_STATUS, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setDisasterDetail_withAddedJitterTtl_3600to3720s() {
        DisasterDetailCacheValue value = new DisasterDetailCacheValue(
            1, 42L, "FLOOD", null, "ALERT", "WARNING", 3,
            "서울", "2026-04-22T10:00:00", null, "홍수 경보", "KMA", true
        );

        cacheWriter.setDisasterDetail(42L, value);

        verify(valueOps).set(
            eq(RedisKeyConstants.disasterDetail(42L)),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.DISASTER_DETAIL, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setDisasterMessagesRecent_withAddedJitterTtl_3600to3720s() {
        cacheWriter.setDisasterMessagesRecent(List.of());

        verify(valueOps).set(
            eq(RedisKeyConstants.DISASTER_MESSAGES_RECENT),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.DISASTER_MESSAGES_RECENT, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setDisasterMessageCore_withAddedJitterTtl_3600to3720s() {
        DisasterMessageItem item = new DisasterMessageItem(
            1, 42L, "FLOOD", null, "ALERT", "WARNING", 3,
            "서울", "2026-04-22T10:00:00", null, "홍수 경보", "KMA", true
        );

        cacheWriter.setDisasterMessageCore(item);

        verify(valueOps).set(
            eq(RedisKeyConstants.DISASTER_MESSAGE_CORE),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.DISASTER_MESSAGE_CORE, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setDisasterMessageCoreEmpty_withAddedJitterTtl_3600to3720s() {
        cacheWriter.setDisasterMessageCoreEmpty();

        verify(valueOps).set(
            eq(RedisKeyConstants.DISASTER_MESSAGE_CORE),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.DISASTER_MESSAGE_CORE, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setDisasterMessagesList_withAddedJitterTtl_3600to3720s() {
        cacheWriter.setDisasterMessagesList(List.of());

        verify(valueOps).set(
            eq(RedisKeyConstants.DISASTER_MESSAGES_LIST),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.DISASTER_MESSAGES_LIST, RedisTtlConstants.SHELTER_DISASTER_JITTER))
        );
    }

    @Test
    void setEnvironmentWeather_canonical_key_SET_noJitter() {
        cacheWriter.setEnvironmentWeather(new WeatherCacheValue(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00"));

        verify(valueOps).set(
            eq(RedisKeyConstants.ENVIRONMENT_WEATHER),
            anyString(),
            eq(RedisTtlConstants.ENVIRONMENT_WEATHER)
        );
    }

    @Test
    void setEnvironmentAirQuality_canonical_key_SET_noJitter() {
        cacheWriter.setEnvironmentAirQuality(new AirQualityCacheValue("종로구", 42, "GOOD", "2026-04-22T10:00:00"));

        verify(valueOps).set(
            eq(RedisKeyConstants.ENVIRONMENT_AIR_QUALITY),
            anyString(),
            eq(RedisTtlConstants.ENVIRONMENT_AIR_QUALITY)
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
    void setEnvironmentWeather_직렬화_실패시_EventProcessingException() {
        ObjectMapper brokenMapper = mock(ObjectMapper.class, invocation -> {
            throw new com.fasterxml.jackson.core.JsonProcessingException("serialization error") {};
        });
        WorkerMetrics metricsForBroken = new WorkerMetrics(new SimpleMeterRegistry());
        RedisCacheWriter writerWithBrokenMapper = new RedisCacheWriter(redisTemplate, brokenMapper, metricsForBroken);

        assertThatThrownBy(() ->
            writerWithBrokenMapper.setEnvironmentWeather(
                new WeatherCacheValue(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00"))
        ).isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("Redis SET serialization failed");
    }

    private static boolean isWithinAddedJitterRange(Duration actual, Duration base, Duration maxJitter) {
        long baseMs = base.toMillis();
        long maxJitterMs = maxJitter.toMillis();
        return actual.toMillis() >= baseMs && actual.toMillis() <= baseMs + maxJitterMs;
    }
}
