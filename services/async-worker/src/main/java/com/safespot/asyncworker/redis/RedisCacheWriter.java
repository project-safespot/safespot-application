package com.safespot.asyncworker.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.service.shelter.ShelterStatusValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheWriter {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerMetrics workerMetrics;

    // Shelter

    public void setShelterDetail(Long shelterId, ShelterDetailValue value) {
        setWithSizeMetric(
                RedisKeyConstants.shelterDetail(shelterId),
                value,
                RedisTtlConstants.withAddedJitter(RedisTtlConstants.SHELTER_DETAIL, RedisTtlConstants.SHELTER_DISASTER_JITTER),
                "shelter_detail");
    }

    public void setShelterStatus(Long shelterId, ShelterStatusValue value) {
        set(RedisKeyConstants.shelterStatus(shelterId), value,
                RedisTtlConstants.withAddedJitter(RedisTtlConstants.SHELTER_STATUS, RedisTtlConstants.SHELTER_DISASTER_JITTER));
    }

    public void setShelterMapItem(Long shelterId, ShelterMapItemValue value) {
        setPersistentWithSizeMetric(RedisKeyConstants.shelterMapItem(shelterId), value, "shelter_map_item");
    }

    public void geoAddShelter(String disasterType, String shelterType, double longitude, double latitude, Long shelterId) {
        geoAddShelterToKey(RedisKeyConstants.shelterGeo(disasterType, shelterType), longitude, latitude, shelterId);
    }

    public void geoAddShelterToKey(String key, double longitude, double latitude, Long shelterId) {
        String eventType = currentEventType();
        try {
            redisTemplate.opsForGeo().add(key, new Point(longitude, latitude), String.valueOf(shelterId));
            workerMetrics.incrementRedisWrite(eventType, "GEOADD", "success");
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "GEOADD", "failure");
            log.error("Redis GEOADD failed: key={}, shelterId={}", key, shelterId, e);
            throw new RedisCacheException("Redis GEOADD failed: key=" + key, e);
        }
    }

    public void setShelterMapTile(int z, int x, int y, String disasterType, String shelterType, List<Long> shelterIds) {
        setShelterMapTileToKey(
            RedisKeyConstants.shelterMapTile(z, x, y, disasterType, shelterType),
            shelterIds
        );
    }

    public void setShelterMapTileToKey(String key, List<Long> shelterIds) {
        List<Long> sortedShelterIds = shelterIds.stream().sorted().toList();
        setPersistentWithSizeMetric(key, sortedShelterIds, "shelter_map_tile");
    }

    // Disaster read models

    public void setDisasterDetail(Long alertId, DisasterDetailCacheValue value) {
        Duration ttl = RedisTtlConstants.withAddedJitter(RedisTtlConstants.DISASTER_DETAIL, RedisTtlConstants.SHELTER_DISASTER_JITTER);
        setWithSizeMetric(RedisKeyConstants.disasterDetail(alertId), value, ttl, "disaster_detail");
    }

    public void setDisasterMessagesRecent(List<DisasterMessageItem> items) {
        Duration ttl = RedisTtlConstants.withAddedJitter(RedisTtlConstants.DISASTER_MESSAGES_RECENT, RedisTtlConstants.SHELTER_DISASTER_JITTER);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGES_RECENT, items, ttl, "disaster_messages_recent");
    }

    public void setDisasterMessageCore(DisasterMessageItem item) {
        Duration ttl = RedisTtlConstants.withAddedJitter(RedisTtlConstants.DISASTER_MESSAGE_CORE, RedisTtlConstants.SHELTER_DISASTER_JITTER);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGE_CORE, item, ttl, "disaster_message_core");
    }

    public void setDisasterMessageCoreEmpty() {
        // core candidate 없음 — schemaVersion=1, 나머지 null인 empty wrapper
        DisasterMessageItem empty = new DisasterMessageItem(1, null, null, null, null, null, null, null, null, null, null, null, null);
        Duration ttl = RedisTtlConstants.withAddedJitter(RedisTtlConstants.DISASTER_MESSAGE_CORE, RedisTtlConstants.SHELTER_DISASTER_JITTER);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGE_CORE, empty, ttl, "disaster_message_core");
    }

    public void setDisasterMessagesList(List<DisasterMessageItem> items) {
        Duration ttl = RedisTtlConstants.withAddedJitter(RedisTtlConstants.DISASTER_MESSAGES_LIST, RedisTtlConstants.SHELTER_DISASTER_JITTER);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGES_LIST, items, ttl, "disaster_messages_list");
    }

    // Environment read models

    public void setEnvironmentWeather(WeatherCacheValue value) {
        set(RedisKeyConstants.ENVIRONMENT_WEATHER, value, RedisTtlConstants.ENVIRONMENT_WEATHER);
    }

    public void setEnvironmentAirQuality(AirQualityCacheValue value) {
        set(RedisKeyConstants.ENVIRONMENT_AIR_QUALITY, value, RedisTtlConstants.ENVIRONMENT_AIR_QUALITY);
    }

    public void setEnvironmentWeatherAlert(WeatherAlertCacheValue value) {
        set(RedisKeyConstants.ENVIRONMENT_WEATHER_ALERT, value, RedisTtlConstants.ENVIRONMENT_WEATHER_ALERT);
    }

    public void deleteDisasterDetail(Long alertId) {
        delete(RedisKeyConstants.disasterDetail(alertId));
    }

    public void deleteKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String eventType = currentEventType();
        try {
            redisTemplate.delete(keys);
            workerMetrics.incrementRedisWrite(eventType, "DEL", "success");
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "DEL", "failure");
            log.error("Redis DEL failed: keyCount={}", keys.size(), e);
            throw new RedisCacheException("Redis DEL failed: keys=" + keys.size(), e);
        }
    }

    public void deleteByPattern(String pattern) {
        String eventType = currentEventType();
        try {
            Long deletedCount = redisTemplate.execute((RedisConnection connection) -> {
                ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
                long totalDeleted = 0L;
                List<byte[]> batch = new ArrayList<>(500);
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= 500) {
                            totalDeleted += deleteBatch(connection, batch);
                        }
                    }
                    totalDeleted += deleteBatch(connection, batch);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return totalDeleted;
            });
            workerMetrics.incrementRedisWrite(eventType, "DEL", "success");
            log.info("Redis DEL by pattern completed: pattern={}, deletedCount={}", pattern, deletedCount != null ? deletedCount : 0L);
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "DEL", "failure");
            log.error("Redis DEL by pattern failed: pattern={}", pattern, e);
            throw new RedisCacheException("Redis DEL by pattern failed: pattern=" + pattern, e);
        }
    }

    public void renameKey(String sourceKey, String targetKey) {
        String eventType = currentEventType();
        try {
            redisTemplate.execute((RedisConnection connection) -> {
                // RENAME은 기존 target key를 덮어쓴다. Phase 2 full rebuild swap에서는 이 동작을 의도적으로 사용한다.
                connection.rename(bytes(sourceKey), bytes(targetKey));
                return null;
            });
            workerMetrics.incrementRedisWrite(eventType, "RENAME", "success");
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "RENAME", "failure");
            log.error("Redis RENAME failed: sourceKey={}, targetKey={}", sourceKey, targetKey, e);
            throw new RedisCacheException("Redis RENAME failed: sourceKey=" + sourceKey + ", targetKey=" + targetKey, e);
        }
    }

    // private

    private long deleteBatch(RedisConnection connection, List<byte[]> batch) {
        if (batch.isEmpty()) {
            return 0L;
        }
        byte[][] keyBytes = batch.toArray(byte[][]::new);
        long deleted = connection.del(keyBytes);
        batch.clear();
        return deleted;
    }

    private byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private void setWithSizeMetric(String key, Object value, Duration ttl, String cacheKeyFamily) {
        String eventType = currentEventType();
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
            workerMetrics.incrementRedisWrite(eventType, "SET", "success");
            workerMetrics.recordRedisPayloadSize(cacheKeyFamily, json.length());
        } catch (JsonProcessingException e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET serialization failed: key={}", key, e);
            throw new EventProcessingException("Redis SET serialization failed: key=" + key, e);
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET failed: key={}", key, e);
            throw new RedisCacheException("Redis SET failed: key=" + key, e);
        }
    }

    private void setPersistentWithSizeMetric(String key, Object value, String cacheKeyFamily) {
        String eventType = currentEventType();
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
            workerMetrics.incrementRedisWrite(eventType, "SET", "success");
            workerMetrics.recordRedisPayloadSize(cacheKeyFamily, json.length());
        } catch (JsonProcessingException e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET serialization failed: key={}", key, e);
            throw new EventProcessingException("Redis SET serialization failed: key=" + key, e);
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET failed: key={}", key, e);
            throw new RedisCacheException("Redis SET failed: key=" + key, e);
        }
    }

    private void set(String key, Object value, Duration ttl) {
        String eventType = currentEventType();
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
            workerMetrics.incrementRedisWrite(eventType, "SET", "success");
        } catch (JsonProcessingException e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET serialization failed: key={}", key, e);
            throw new EventProcessingException("Redis SET serialization failed: key=" + key, e);
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "SET", "failure");
            log.error("Redis SET failed: key={}", key, e);
            throw new RedisCacheException("Redis SET failed: key=" + key, e);
        }
    }

    private void delete(String key) {
        String eventType = currentEventType();
        try {
            redisTemplate.delete(key);
            workerMetrics.incrementRedisWrite(eventType, "DEL", "success");
        } catch (Exception e) {
            workerMetrics.incrementRedisWrite(eventType, "DEL", "failure");
            log.error("Redis DEL failed: key={}", key, e);
            throw new RedisCacheException("Redis DEL failed: key=" + key, e);
        }
    }

    private String currentEventType() {
        String eventType = MDC.get("eventType");
        return eventType != null ? eventType : "unknown";
    }
}
