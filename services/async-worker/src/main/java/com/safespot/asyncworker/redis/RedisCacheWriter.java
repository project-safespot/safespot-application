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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheWriter {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkerMetrics workerMetrics;

    // Shelter

    public void setShelterStatus(Long shelterId, ShelterStatusValue value) {
        set(RedisKeyConstants.shelterStatus(shelterId), value, RedisTtlConstants.withJitter(RedisTtlConstants.SHELTER_STATUS, 0.1));
    }

    // Disaster read models

    public void setDisasterDetail(Long alertId, DisasterDetailCacheValue value) {
        setWithSizeMetric(RedisKeyConstants.disasterDetail(alertId), value, RedisTtlConstants.DISASTER_DETAIL, "disaster_detail");
    }

    public void setDisasterMessagesRecent(List<DisasterMessageItem> items) {
        Duration ttl = RedisTtlConstants.withJitter(RedisTtlConstants.DISASTER_MESSAGES_RECENT, 0.1);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGES_RECENT, items, ttl, "disaster_messages_recent");
    }

    public void setDisasterMessageCore(DisasterMessageItem item) {
        Duration ttl = RedisTtlConstants.withJitter(RedisTtlConstants.DISASTER_MESSAGE_CORE, 0.1);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGE_CORE, item, ttl, "disaster_message_core");
    }

    public void setDisasterMessageCoreEmpty() {
        // core candidate 없음 — schemaVersion=1, 나머지 null인 empty wrapper
        DisasterMessageItem empty = new DisasterMessageItem(1, null, null, null, null, null, null, null, null, null, null, null, null);
        Duration ttl = RedisTtlConstants.withJitter(RedisTtlConstants.DISASTER_MESSAGE_CORE, 0.1);
        setWithSizeMetric(RedisKeyConstants.DISASTER_MESSAGE_CORE, empty, ttl, "disaster_message_core");
    }

    public void setDisasterMessagesList(List<DisasterMessageItem> items) {
        Duration ttl = RedisTtlConstants.withJitter(RedisTtlConstants.DISASTER_MESSAGES_LIST, 0.1);
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

    // private

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
