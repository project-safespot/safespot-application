package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisReadCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public enum FallbackReason { REDIS_MISS, REDIS_DOWN, PARSE_ERROR }

    public record CacheResult<T>(T value, FallbackReason fallbackReason) {
        public boolean isHit() { return value != null; }
        public boolean isMiss() { return value == null && fallbackReason == FallbackReason.REDIS_MISS; }
        public boolean isDown() { return value == null && fallbackReason == FallbackReason.REDIS_DOWN; }
        public boolean isParseError() { return value == null && fallbackReason == FallbackReason.PARSE_ERROR; }

        public String resultLabel() {
            if (value != null) return "hit";
            return switch (fallbackReason) {
                case REDIS_DOWN -> "down";
                case PARSE_ERROR -> "parse_error";
                case REDIS_MISS -> "miss";
            };
        }
    }

    public <T> CacheResult<T> get(String key, TypeReference<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return new CacheResult<>(null, FallbackReason.REDIS_MISS);
            }
            T value = objectMapper.readValue(json, type);
            return new CacheResult<>(value, null);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failure for key={}: {}", key, e.getMessage());
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN);
        } catch (DataAccessException e) {
            log.warn("Redis error for key={}: {}", key, e.getMessage());
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN);
        } catch (Exception e) {
            log.warn("Redis read/parse error for key={}: {}", key, e.getMessage());
            return new CacheResult<>(null, FallbackReason.PARSE_ERROR);
        }
    }

    public void set(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis set failed (connection) for key={}: {}", key, e.getMessage());
        } catch (DataAccessException e) {
            log.warn("Redis set failed for key={}: {}", key, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis set/serialize error for key={}: {}", key, e.getMessage());
        }
    }

    public void recordCacheRequest(String endpoint, String result) {
        meterRegistry.counter("api_read_cache_request_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "result", result
        ).increment();
    }

    public void recordFallback(String endpoint, FallbackReason reason) {
        String reasonLabel = switch (reason) {
            case REDIS_DOWN -> "redis_down";
            case PARSE_ERROR -> "parse_error";
            case REDIS_MISS -> "redis_miss";
        };
        meterRegistry.counter("api_read_cache_fallback_total",
                "service", "api-public-read",
                "endpoint", endpoint,
                "reason", reasonLabel
        ).increment();
    }

    public void recordDbFallbackQuery(String endpoint) {
        meterRegistry.counter("api_read_db_fallback_query_total",
                "service", "api-public-read",
                "endpoint", endpoint
        ).increment();
    }

    public void recordDbFallbackLatency(String endpoint, long durationMs) {
        Timer.builder("api_read_db_fallback_latency_seconds")
                .tag("service", "api-public-read")
                .tag("endpoint", endpoint)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
