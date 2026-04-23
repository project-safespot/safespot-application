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

    public void setShelterStatus(Long shelterId, ShelterStatusValue value) {
        String key = RedisKeyConstants.shelterStatus(shelterId);
        set(key, value, RedisTtlConstants.SHELTER_STATUS);
    }

    public void setWeather(WeatherCacheValue value) {
        String key = RedisKeyConstants.envWeather(value.nx(), value.ny());
        set(key, value, RedisTtlConstants.ENV_WEATHER);
    }

    public void setAirQuality(AirQualityCacheValue value) {
        String key = RedisKeyConstants.envAir(value.stationName());
        set(key, value, RedisTtlConstants.ENV_AIR);
    }

    public void deleteDisasterActive(String region) {
        String key = RedisKeyConstants.disasterActive(region);
        delete(key);
    }

    public void setDisasterActive(String region, List<DisasterActiveItem> items) {
        String key = RedisKeyConstants.disasterActive(region);
        set(key, items, RedisTtlConstants.DISASTER_ACTIVE);
    }

    public void setDisasterAlertList(String region, String disasterType, List<DisasterAlertListItem> items) {
        String key = RedisKeyConstants.disasterAlertList(region, disasterType);
        set(key, items, RedisTtlConstants.DISASTER_ALERT_LIST);
    }

    public void setDisasterDetail(Long alertId, DisasterDetailCacheValue value) {
        String key = RedisKeyConstants.disasterDetail(alertId);
        set(key, value, RedisTtlConstants.DISASTER_DETAIL);
    }

    public void setDisasterLatest(String disasterType, String region, DisasterLatestCacheValue value) {
        String key = RedisKeyConstants.disasterLatest(disasterType, region);
        set(key, value, RedisTtlConstants.DISASTER_LATEST);
    }

    public void deleteDisasterLatest(String disasterType, String region) {
        String key = RedisKeyConstants.disasterLatest(disasterType, region);
        delete(key);
    }

    private void set(String key, Object value, Duration ttl) {
        String eventType = currentEventType();
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
            workerMetrics.incrementRedisWrite(eventType, "SET", "success");
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 재시도해도 해결되지 않으므로 non-retriable로 분류
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
