package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

    public record GeoSearchHit(Long shelterId, double distanceM) {}

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

    public Map<Long, CacheResult<ShelterStatusCacheDto>> multiGetShelterStatus(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) return Map.of();

        List<Long> distinctIds = shelterIds.stream().distinct().toList();
        List<String> keys = distinctIds.stream()
                .map(id -> "shelter:status:" + id)
                .toList();

        long start = System.nanoTime();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
            recordRedisRead("shelter_status", "success", start);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis MGET connection failure for shelter_status: {}", e.getMessage());
            recordRedisRead("shelter_status", "failure", start);
            return allDown(distinctIds);
        } catch (DataAccessException e) {
            log.warn("Redis MGET error for shelter_status: {}", e.getMessage());
            recordRedisRead("shelter_status", "failure", start);
            return allDown(distinctIds);
        }

        if (values == null) values = Collections.nCopies(distinctIds.size(), null);

        Map<Long, CacheResult<ShelterStatusCacheDto>> result = new LinkedHashMap<>();
        int parseErrors = 0;
        for (int i = 0; i < distinctIds.size(); i++) {
            Long id = distinctIds.get(i);
            String json = (i < values.size()) ? values.get(i) : null;
            if (json == null) {
                result.put(id, new CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_status"));
            } else {
                try {
                    ShelterStatusCacheDto dto = objectMapper.readValue(json, ShelterStatusCacheDto.class);
                    result.put(id, new CacheResult<>(dto, null, "shelter_status"));
                } catch (Exception e) {
                    parseErrors++;
                    log.warn("Redis MGET parse error for shelter:status:{}: {}", id, e.getMessage());
                    result.put(id, new CacheResult<>(null, FallbackReason.PARSE_ERROR, "shelter_status"));
                }
            }
        }
        if (parseErrors > 0) {
            meterRegistry.counter("safespot.cache.shelter_status.mget.parse_error",
                    "service", "api-public-read").increment(parseErrors);
        }
        return result;
    }

    public CacheResult<List<GeoSearchHit>> geoSearchShelterIds(String key, double longitude, double latitude, double radiusM, int limit) {
        long start = System.nanoTime();
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                    key,
                    GeoReference.fromCoordinate(new Point(longitude, latitude)),
                    new Distance(radiusM / 1000d, Metrics.KILOMETERS),
                    GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(limit)
            );
            List<GeoSearchHit> hits = new ArrayList<>();
            if (results != null) {
                results.forEach(result -> {
                    String member = result.getContent().getName();
                    try {
                        hits.add(new GeoSearchHit(Long.parseLong(member), result.getDistance() != null ? result.getDistance().getValue() : 0d));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid GEO member: " + member, e);
                    }
                });
            }
            if (!hits.isEmpty()) {
                recordRedisRead("shelter_geo_index", "success", start);
                return new CacheResult<>(hits, null, "shelter_geo_index");
            }

            Boolean hasKey = redisTemplate.hasKey(key);
            recordRedisRead("shelter_geo_index", "success", start);
            if (Boolean.TRUE.equals(hasKey)) {
                return new CacheResult<>(List.of(), null, "shelter_geo_index");
            }
            return new CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_geo_index");
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis GEOSEARCH connection failure for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start);
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, "shelter_geo_index");
        } catch (DataAccessException e) {
            log.warn("Redis GEOSEARCH error for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start);
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, "shelter_geo_index");
        } catch (Exception e) {
            log.warn("Redis GEOSEARCH parse/error for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start);
            return new CacheResult<>(null, FallbackReason.PARSE_ERROR, "shelter_geo_index");
        }
    }

    public Map<Long, CacheResult<ShelterMapItemCacheDto>> multiGetShelterMapItems(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) return Map.of();

        List<Long> distinctIds = shelterIds.stream().distinct().toList();
        List<String> keys = distinctIds.stream()
                .map(id -> "shelter:map:item:" + id)
                .toList();

        long start = System.nanoTime();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(keys);
            recordRedisRead("shelter_map_item", "success", start);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis MGET connection failure for shelter_map_item: {}", e.getMessage());
            recordRedisRead("shelter_map_item", "failure", start);
            return allDown(distinctIds, "shelter_map_item");
        } catch (DataAccessException e) {
            log.warn("Redis MGET error for shelter_map_item: {}", e.getMessage());
            recordRedisRead("shelter_map_item", "failure", start);
            return allDown(distinctIds, "shelter_map_item");
        }

        if (values == null) values = Collections.nCopies(distinctIds.size(), null);

        Map<Long, CacheResult<ShelterMapItemCacheDto>> result = new LinkedHashMap<>();
        for (int i = 0; i < distinctIds.size(); i++) {
            Long id = distinctIds.get(i);
            String json = (i < values.size()) ? values.get(i) : null;
            if (json == null) {
                result.put(id, new CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_map_item"));
            } else {
                try {
                    ShelterMapItemCacheDto dto = objectMapper.readValue(json, ShelterMapItemCacheDto.class);
                    result.put(id, new CacheResult<>(dto, null, "shelter_map_item"));
                } catch (Exception e) {
                    log.warn("Redis MGET parse error for shelter:map:item:{}: {}", id, e.getMessage());
                    result.put(id, new CacheResult<>(null, FallbackReason.PARSE_ERROR, "shelter_map_item"));
                }
            }
        }
        return result;
    }

    public Map<String, CacheResult<List<Long>>> multiGetShelterMapTiles(List<String> tileKeys) {
        if (tileKeys == null || tileKeys.isEmpty()) {
            return Map.of();
        }
        long start = System.nanoTime();
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(tileKeys);
            recordRedisRead("shelter_map_tile", "success", start);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis MGET connection failure for shelter_map_tile: {}", e.getMessage());
            recordRedisRead("shelter_map_tile", "failure", start);
            return buildAllDown(tileKeys, "shelter_map_tile");
        } catch (DataAccessException e) {
            log.warn("Redis MGET error for shelter_map_tile: {}", e.getMessage());
            recordRedisRead("shelter_map_tile", "failure", start);
            return buildAllDown(tileKeys, "shelter_map_tile");
        }

        if (values == null) values = Collections.nCopies(tileKeys.size(), null);
        Map<String, CacheResult<List<Long>>> result = new LinkedHashMap<>();
        for (int i = 0; i < tileKeys.size(); i++) {
            String key = tileKeys.get(i);
            String json = (i < values.size()) ? values.get(i) : null;
            if (json == null) {
                result.put(key, new CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_map_tile"));
            } else {
                try {
                    List<Long> ids = objectMapper.readValue(json, new TypeReference<List<Long>>() {});
                    result.put(key, new CacheResult<>(ids, null, "shelter_map_tile"));
                } catch (Exception e) {
                    log.warn("Redis MGET parse error for {}: {}", key, e.getMessage());
                    result.put(key, new CacheResult<>(null, FallbackReason.PARSE_ERROR, "shelter_map_tile"));
                }
            }
        }
        return result;
    }

    private Map<Long, CacheResult<ShelterStatusCacheDto>> allDown(List<Long> ids) {
        Map<Long, CacheResult<ShelterStatusCacheDto>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, new CacheResult<>(null, FallbackReason.REDIS_DOWN, "shelter_status"));
        }
        return result;
    }

    private <T> Map<Long, CacheResult<T>> allDown(List<Long> ids, String cache) {
        Map<Long, CacheResult<T>> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache));
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
        if (key.startsWith("shelter:geo:")) return "shelter_geo_index";
        if (key.startsWith("shelter:map:item:")) return "shelter_map_item";
        if (key.startsWith("shelter:map:tile:")) return "shelter_map_tile";
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
