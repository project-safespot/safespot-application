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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheWriterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private GeoOperations<String, String> geoOps;
    @Mock private RedisConnection redisConnection;
    @Mock private Cursor<byte[]> cursor;

    private RedisCacheWriter cacheWriter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForGeo()).thenReturn(geoOps);
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
    void setShelterDetail_withAddedJitterTtl_3600to3720s() {
        cacheWriter.setShelterDetail(
            101L,
            new ShelterDetailValue(1, 101L, "상세 대피소", "DESIGNATED", "FLOOD", "서울", 37.55, 126.98, 120, "manager", "010", "note", "2026-05-15T10:00:00Z")
        );

        verify(valueOps).set(
            eq(RedisKeyConstants.shelterDetail(101L)),
            anyString(),
            argThat(ttl -> isWithinAddedJitterRange(ttl,
                    RedisTtlConstants.SHELTER_DETAIL, RedisTtlConstants.SHELTER_DISASTER_JITTER))
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
    void setShelterMapItem_persistent_SET_withoutTtl() {
        cacheWriter.setShelterMapItem(
            101L,
            new ShelterMapItemValue(1, 101L, "테스트 대피소", "DESIGNATED", "FLOOD", "서울", 120, 37.55, 126.98, "2026-05-15T10:00:00Z")
        );

        verify(valueOps).set(
            eq(RedisKeyConstants.shelterMapItem(101L)),
            anyString()
        );
        verify(valueOps, never()).set(eq(RedisKeyConstants.shelterMapItem(101L)), anyString(), any(Duration.class));
    }

    @Test
    void geoAddShelter_GEOADD_좌표순서와_member문자열을_사용한다() {
        cacheWriter.geoAddShelter("FLOOD", "DESIGNATED", 126.9780, 37.5665, 101L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Point> pointCaptor = ArgumentCaptor.forClass(Point.class);
        ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);

        verify(geoOps).add(eq(RedisKeyConstants.shelterGeo("FLOOD", "DESIGNATED")), pointCaptor.capture(), memberCaptor.capture());
        assertThat(pointCaptor.getValue().getX()).isEqualTo(126.9780);
        assertThat(pointCaptor.getValue().getY()).isEqualTo(37.5665);
        assertThat(memberCaptor.getValue()).isEqualTo("101");
    }

    @Test
    void geoAddShelterToKey_GEOADD_지정_key를_사용한다() {
        cacheWriter.geoAddShelterToKey("shelter:geo:tmp:run:seoul:FLOOD:DESIGNATED", 126.9780, 37.5665, 101L);

        verify(geoOps).add(eq("shelter:geo:tmp:run:seoul:FLOOD:DESIGNATED"), any(Point.class), eq("101"));
    }

    @Test
    void setShelterMapTile_shelterId_오름차순_JSON_array로_지속저장한다() {
        cacheWriter.setShelterMapTile(13, 7285, 3172, "FLOOD", "DESIGNATED", List.of(9L, 3L, 5L));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
            eq(RedisKeyConstants.shelterMapTile(13, 7285, 3172, "FLOOD", "DESIGNATED")),
            jsonCaptor.capture()
        );
        assertThat(jsonCaptor.getValue()).isEqualTo("[3,5,9]");
    }

    @Test
    void setShelterMapTileToKey_지정_key에_오름차순_JSON_array로_지속저장한다() {
        cacheWriter.setShelterMapTileToKey("shelter:map:tmp:tile:run:13:7285:3172:FLOOD:DESIGNATED", List.of(9L, 3L, 5L));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(
            eq("shelter:map:tmp:tile:run:13:7285:3172:FLOOD:DESIGNATED"),
            jsonCaptor.capture()
        );
        assertThat(jsonCaptor.getValue()).isEqualTo("[3,5,9]");
    }

    @Test
    void deleteKeys_컬렉션_전체를_삭제한다() {
        cacheWriter.deleteKeys(List.of("a", "b"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keysCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(keysCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly("a", "b");
    }

    @Test
    void deleteByPattern_매칭된_경우_execute를_통해_삭제한다() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(2L);

        cacheWriter.deleteByPattern("shelter:map:tile:*");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void deleteByPattern_매칭_없으면_no_op() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(0L);

        cacheWriter.deleteByPattern("shelter:map:tile:*");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void deleteByPattern_SCAN_실패시_RedisCacheException() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("scan failed"));

        assertThatThrownBy(() -> cacheWriter.deleteByPattern("shelter:map:tile:*"))
            .isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("Redis DEL by pattern failed");
    }

    @Test
    void renameKey_RENAME을_호출한다() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(redisConnection);
        });
        cacheWriter.renameKey("source", "target");

        verify(redisConnection).rename(bytes("source"), bytes("target"));
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

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
