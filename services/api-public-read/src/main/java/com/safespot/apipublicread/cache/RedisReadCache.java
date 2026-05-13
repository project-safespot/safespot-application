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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public record CacheResult<T>(T value, FallbackReason fallbackReason, String cache) {
        public CacheResult(T value, FallbackReason fallbackReason) {
            this(value, fallbackReason, "unknown");
        }

        public boolean isHit() { return value != null; }
        public boolean isMiss() { return value == null && fallbackReason == FallbackReason.REDIS_MISS; }
        public boolean isDown() { return value == null && fallbackReason == FallbackReason.REDIS_DOWN; }
        public boolean isParseError() { return value == null && fallbackReason == FallbackReason.PARSE_ERROR; }

        public String resultLabel() {
            if (value != null) return "hit";
            return switch (fallbackReason) {
                case REDIS_DOWN -> "miss";
                case PARSE_ERROR -> "parse_error";
                case REDIS_MISS -> "miss";
            };
        }
    }

    public <T> CacheResult<T> get(String key, TypeReference<T> type) {
        String cache = cacheName(key);
        long start = System.nanoTime();
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                recordRedisRead(cache, "success", start);
                return new CacheResult<>(null, FallbackReason.REDIS_MISS, cache);
            }
            T value = objectMapper.readValue(json, type);
            recordRedisRead(cache, "success", start);
            return new CacheResult<>(value, null, cache);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failure for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start);
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache);
        } catch (DataAccessException e) {
            log.warn("Redis error for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start);
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache);
        } catch (Exception e) {
            log.warn("Redis read/parse error for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start);
            return new CacheResult<>(null, FallbackReason.PARSE_ERROR, cache);
        }
    }

    public <T> Map<String, CacheResult<T>> getAll(List<String> keys, TypeReference<T> type) {
        if (keys == null || keys.isEmpty()) return Map.of();
        String cache = cacheName(keys.get(0));
        long start = System.nanoTime();
        try {
            List<String> jsonValues = redisTemplate.opsForValue().multiGet(keys);
            recordRedisRead(cache, "success", start);
            Map<String, CacheResult<T>> result = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                String json = (jsonValues != null) ? jsonValues.get(i) : null;
                if (json == null) {
                    result.put(key, new CacheResult<>(null, FallbackReason.REDIS_MISS, cache));
                } else {
                    try {
                        T value = objectMapper.readValue(json, type);
                        result.put(key, new CacheResult<>(value, null, cache));
                    } catch (Exception e) {
                        log.warn("Redis parse error for key={}: {}", key, e.getMessage());
                        result.put(key, new CacheResult<>(null, FallbackReason.PARSE_ERROR, cache));
                    }
                }
            }
            return result;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failure for MGET (count={}): {}", keys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start);
            return buildAllDown(keys, cache);
        } catch (DataAccessException e) {
            log.warn("Redis error for MGET (count={}): {}", keys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start);
            return buildAllDown(keys, cache);
        } catch (Exception e) {
            log.warn("Redis unexpected error for MGET (count={}): {}", keys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start);
            return buildAllDown(keys, cache);
        }
    }

    private <T> Map<String, CacheResult<T>> buildAllDown(List<String> keys, String cache) {
        Map<String, CacheResult<T>> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache));
        }
        return result;
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

    public void recordCacheRequest(String cache, String result) {
        meterRegistry.counter("safespot.cache.requests",
                "service", "api-public-read",
                "cache", lowCardinality(cache),
                "result", result
        ).increment();
    }

    public void recordFallback(String cache, FallbackReason reason) {
        String reasonLabel = switch (reason) {
            case REDIS_DOWN -> "redis_down";
            case PARSE_ERROR -> "parse_error";
            case REDIS_MISS -> "redis_miss";
        };
        meterRegistry.counter("safespot.cache.fallback",
                "service", "api-public-read",
                "cache", lowCardinality(cache),
                "reason", reasonLabel
        ).increment();
    }

    public void recordDbFallbackQuery(String repository, FallbackReason reason) {
        meterRegistry.counter("safespot.db.fallback.queries",
                "service", "api-public-read",
                "repository", lowCardinality(repository),
                "reason", dbFallbackReason(reason)
        ).increment();
    }

    public void recordDbFallbackLatency(String repository, String result, long durationMs) {
        Timer.builder("safespot.db.fallback")
                .tag("service", "api-public-read")
                .tag("repository", lowCardinality(repository))
                .tag("result", lowCardinality(result))
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private void recordRedisRead(String cache, String result, long startNanos) {
        Timer.builder("safespot.redis.read")
                .tag("service", "api-public-read")
                .tag("cache", lowCardinality(cache))
                .tag("result", lowCardinality(result))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private static String dbFallbackReason(FallbackReason reason) {
        return switch (reason) {
            case REDIS_DOWN -> "redis_error";
            case PARSE_ERROR -> "parse_error";
            case REDIS_MISS -> "cache_miss";
        };
    }

    private static String cacheName(String key) {
        if (key == null) return "unknown";
        if (key.equals("disaster:messages:list:seoul")) return "disaster_messages";
        if (key.equals("disaster:messages:recent:seoul")) return "disaster_messages";
        if (key.equals("disaster:message:core:seoul")) return "disaster_messages";
        if (key.startsWith("disaster:detail:")) return "disaster_detail";
        if (key.startsWith("shelter:status:")) return "shelter_status";
        if (key.equals("environment:weather:seoul")) return "weather";
        if (key.equals("environment:air-quality:seoul")) return "air_quality";
        return "unknown";
    }

    private static String lowCardinality(String value) {
        return Optional.ofNullable(value)
                .filter(v -> v.matches("[a-zA-Z0-9_./{}-]+"))
                .orElse("unknown");
    }
}
