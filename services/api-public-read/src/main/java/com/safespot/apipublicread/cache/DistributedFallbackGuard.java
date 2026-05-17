package com.safespot.apipublicread.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedFallbackGuard {

    public enum Decision {
        LEADER,
        BLOCKED,
        ERROR
    }

    private final StringRedisTemplate redisTemplate;
    private final PublicReadMetricRecorder metricRecorder;

    public Decision tryAcquire(String cache, String region, String logicalKey, Duration ttl) {
        String key = "lock:fallback:%s:%s:%s".formatted(cache, region, logicalKey);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, UUID.randomUUID().toString(), ttl);
            if (Boolean.TRUE.equals(acquired)) {
                metricRecorder.recordFallbackSingleflight(cache, "distributed", "leader");
                return Decision.LEADER;
            }
            metricRecorder.recordFallbackSingleflight(cache, "distributed", "blocked");
            return Decision.BLOCKED;
        } catch (RedisConnectionFailureException e) {
            log.warn("Distributed fallback lock unavailable key={}: {}", key, e.getMessage());
            metricRecorder.recordFallbackSingleflight(cache, "distributed", "error");
            return Decision.ERROR;
        } catch (DataAccessException e) {
            log.warn("Distributed fallback lock failed key={}: {}", key, e.getMessage());
            metricRecorder.recordFallbackSingleflight(cache, "distributed", "error");
            return Decision.ERROR;
        } catch (Exception e) {
            log.warn("Distributed fallback lock unexpected error key={}: {}", key, e.getMessage());
            metricRecorder.recordFallbackSingleflight(cache, "distributed", "error");
            return Decision.ERROR;
        }
    }
}
