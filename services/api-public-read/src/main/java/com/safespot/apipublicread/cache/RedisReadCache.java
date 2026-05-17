package com.safespot.apipublicread.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
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
    private final PublicReadMetricRecorder metricRecorder;

    public RedisReadCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this(redisTemplate, objectMapper, meterRegistry, new PublicReadMetricRecorder(meterRegistry));
    }

    public enum CacheMetricLabel {
        DISASTER_MESSAGES("disaster_messages"),
        DISASTER_DETAIL("disaster_detail"),
        SHELTER_STATUS("shelter_status"),
        SHELTER_GEO_INDEX("shelter_geo_index"),
        SHELTER_MAP_ITEM("shelter_map_item"),
        SHELTER_MAP_TILE("shelter_map_tile"),
        WEATHER("weather"),
        AIR_QUALITY("air_quality"),
        UNKNOWN("unknown");

        private final String value;

        CacheMetricLabel(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

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
                recordRedisRead(cache, "success", start, "single");
                return new CacheResult<>(null, FallbackReason.REDIS_MISS, cache);
            }
            T value = objectMapper.readValue(json, type);
            recordRedisRead(cache, "success", start, "single");
            return new CacheResult<>(value, null, cache);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failure for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache);
        } catch (DataAccessException e) {
            log.warn("Redis error for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, cache);
        } catch (Exception e) {
            log.warn("Redis read/parse error for key={}: {}", key, e.getMessage());
            recordRedisRead(cache, "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.PARSE_ERROR, cache);
        }
    }

    public <T> Map<String, CacheResult<T>> getMany(java.util.Collection<String> keys, Class<T> type, CacheMetricLabel label) {
        if (keys == null || keys.isEmpty()) return Map.of();
        List<String> orderedKeys = List.copyOf(keys);
        String cache = label.value();
        long start = System.nanoTime();
        try {
            List<String> jsonValues = redisTemplate.opsForValue().multiGet(orderedKeys);
            recordRedisRead(cache, "success", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "success");
            Map<String, CacheResult<T>> result = new LinkedHashMap<>();
            for (int i = 0; i < orderedKeys.size(); i++) {
                String key = orderedKeys.get(i);
                String json = (jsonValues != null) ? jsonValues.get(i) : null;
                if (json == null) {
                    result.put(key, new CacheResult<>(null, FallbackReason.REDIS_MISS, cache));
                    continue;
                }
                try {
                    result.put(key, new CacheResult<>(objectMapper.readValue(json, type), null, cache));
                } catch (Exception e) {
                    log.warn("Redis parse error for key={}: {}", key, e.getMessage());
                    result.put(key, new CacheResult<>(null, FallbackReason.PARSE_ERROR, cache));
                }
            }
            return result;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failure for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        } catch (DataAccessException e) {
            log.warn("Redis error for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        } catch (Exception e) {
            log.warn("Redis unexpected error for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        }
    }

    public <T> Map<String, CacheResult<T>> getMany(java.util.Collection<String> keys, TypeReference<T> type, CacheMetricLabel label) {
        if (keys == null || keys.isEmpty()) return Map.of();
        List<String> orderedKeys = List.copyOf(keys);
        String cache = label.value();
        long start = System.nanoTime();
        try {
            List<String> jsonValues = redisTemplate.opsForValue().multiGet(orderedKeys);
            recordRedisRead(cache, "success", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "success");
            Map<String, CacheResult<T>> result = new LinkedHashMap<>();
            for (int i = 0; i < orderedKeys.size(); i++) {
                String key = orderedKeys.get(i);
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
            log.warn("Redis connection failure for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        } catch (DataAccessException e) {
            log.warn("Redis error for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        } catch (Exception e) {
            log.warn("Redis unexpected error for MGET cache={} (count={}): {}", cache, orderedKeys.size(), e.getMessage());
            recordRedisRead(cache, "failure", start, "batch");
            recordRedisBatchSize(cache, orderedKeys.size(), "failure");
            return buildAllDown(orderedKeys, cache);
        }
    }

    public <T> Map<String, CacheResult<T>> getAll(List<String> keys, TypeReference<T> type) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        return getMany(keys, type, metricLabel(keys.get(0)));
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
        Map<String, CacheResult<ShelterStatusCacheDto>> values =
                getMany(keys, ShelterStatusCacheDto.class, CacheMetricLabel.SHELTER_STATUS);
        Map<Long, CacheResult<ShelterStatusCacheDto>> result = new LinkedHashMap<>();
        for (Long id : distinctIds) {
            result.put(id, values.getOrDefault("shelter:status:" + id,
                    new CacheResult<>(null, FallbackReason.REDIS_MISS, CacheMetricLabel.SHELTER_STATUS.value())));
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
                recordRedisRead("shelter_geo_index", "success", start, "single");
                return new CacheResult<>(hits, null, "shelter_geo_index");
            }

            Boolean hasKey = redisTemplate.hasKey(key);
            recordRedisRead("shelter_geo_index", "success", start, "single");
            if (Boolean.TRUE.equals(hasKey)) {
                return new CacheResult<>(List.of(), null, "shelter_geo_index");
            }
            return new CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_geo_index");
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis GEOSEARCH connection failure for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, "shelter_geo_index");
        } catch (DataAccessException e) {
            log.warn("Redis GEOSEARCH error for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.REDIS_DOWN, "shelter_geo_index");
        } catch (Exception e) {
            log.warn("Redis GEOSEARCH parse/error for key={}: {}", key, e.getMessage());
            recordRedisRead("shelter_geo_index", "failure", start, "single");
            return new CacheResult<>(null, FallbackReason.PARSE_ERROR, "shelter_geo_index");
        }
    }

    public Map<Long, CacheResult<ShelterMapItemCacheDto>> multiGetShelterMapItems(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) return Map.of();

        List<Long> distinctIds = shelterIds.stream().distinct().toList();
        List<String> keys = distinctIds.stream()
                .map(id -> "shelter:map:item:" + id)
                .toList();
        Map<String, CacheResult<ShelterMapItemCacheDto>> values =
                getMany(keys, ShelterMapItemCacheDto.class, CacheMetricLabel.SHELTER_MAP_ITEM);
        Map<Long, CacheResult<ShelterMapItemCacheDto>> result = new LinkedHashMap<>();
        for (Long id : distinctIds) {
            result.put(id, values.getOrDefault("shelter:map:item:" + id,
                    new CacheResult<>(null, FallbackReason.REDIS_MISS, CacheMetricLabel.SHELTER_MAP_ITEM.value())));
        }
        return result;
    }

    public Map<String, CacheResult<List<Long>>> multiGetShelterMapTiles(List<String> tileKeys) {
        if (tileKeys == null || tileKeys.isEmpty()) {
            return Map.of();
        }
        return getMany(tileKeys, new TypeReference<List<Long>>() {}, CacheMetricLabel.SHELTER_MAP_TILE);
    }

    public Map<String, CacheResult<List<Long>>> multiGetShelterMapTileStale(List<String> tileKeys) {
        if (tileKeys == null || tileKeys.isEmpty()) {
            return Map.of();
        }
        List<String> staleKeys = tileKeys.stream().map(key -> "stale:" + key).toList();
        Map<String, CacheResult<List<Long>>> values =
                getMany(staleKeys, new TypeReference<List<Long>>() {}, CacheMetricLabel.SHELTER_MAP_TILE);
        Map<String, CacheResult<List<Long>>> result = new LinkedHashMap<>();
        for (String key : tileKeys) {
            result.put(key, values.getOrDefault("stale:" + key,
                    new CacheResult<>(null, FallbackReason.REDIS_MISS, CacheMetricLabel.SHELTER_MAP_TILE.value())));
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
        metricRecorder.recordCacheRequest(cache, result);
    }

    public void recordFallback(String cache, FallbackReason reason) {
        recordFallback(cache, fallbackReasonLabel(reason));
    }

    public void recordFallback(String cache, String reason) {
        metricRecorder.recordCacheFallback(cache, reason);
    }

    public void recordDbFallbackQuery(String cache, String repository, FallbackReason reason, String result) {
        metricRecorder.recordDbFallbackQuery(cache, repository, dbFallbackReason(reason), result);
    }

    public void recordDbFallbackQuery(String cache, String repository, String reason, String result) {
        metricRecorder.recordDbFallbackQuery(cache, repository, reason, result);
    }

    public void recordDbFallbackQuery(String repository, FallbackReason reason) {
        recordDbFallbackQuery("unknown", repository, reason, "leader");
    }

    public void recordDbFallbackLatency(String cache, String repository, String result, long durationMs) {
        metricRecorder.recordDbFallbackLatency(cache, repository, result, durationMs);
    }

    public void recordDbFallbackLatency(String repository, String result, long durationMs) {
        recordDbFallbackLatency("unknown", repository, result, durationMs);
    }

    private void recordRedisRead(String cache, String result, long startNanos, String operation) {
        Timer.builder("safespot.redis.read")
                .tag("service", "api-public-read")
                .tag("cache", lowCardinality(cache))
                .tag("result", lowCardinality(result))
                .tag("operation", lowCardinality(operation))
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private void recordRedisBatchSize(String cache, int batchSize, String result) {
        DistributionSummary.builder("safespot.redis.read.batch.size")
                .tag("service", "api-public-read")
                .tag("cache", lowCardinality(cache))
                .tag("result", lowCardinality(result))
                .register(meterRegistry)
                .record(batchSize);
    }

    private static String dbFallbackReason(FallbackReason reason) {
        return switch (reason) {
            case REDIS_DOWN -> "redis_error";
            case PARSE_ERROR -> "parse_error";
            case REDIS_MISS -> "cache_miss";
        };
    }

    private static String fallbackReasonLabel(FallbackReason reason) {
        return switch (reason) {
            case REDIS_DOWN -> "redis_down";
            case PARSE_ERROR -> "parse_error";
            case REDIS_MISS -> "redis_miss";
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

    private static CacheMetricLabel metricLabel(String key) {
        return switch (cacheName(key)) {
            case "disaster_messages" -> CacheMetricLabel.DISASTER_MESSAGES;
            case "disaster_detail" -> CacheMetricLabel.DISASTER_DETAIL;
            case "shelter_status" -> CacheMetricLabel.SHELTER_STATUS;
            case "shelter_geo_index" -> CacheMetricLabel.SHELTER_GEO_INDEX;
            case "shelter_map_item" -> CacheMetricLabel.SHELTER_MAP_ITEM;
            case "shelter_map_tile" -> CacheMetricLabel.SHELTER_MAP_TILE;
            case "weather" -> CacheMetricLabel.WEATHER;
            case "air_quality" -> CacheMetricLabel.AIR_QUALITY;
            default -> CacheMetricLabel.UNKNOWN;
        };
    }

    private static String lowCardinality(String value) {
        return Optional.ofNullable(value)
                .filter(v -> v.matches("[a-zA-Z0-9_./{}-]+"))
                .orElse("unknown");
    }
}
