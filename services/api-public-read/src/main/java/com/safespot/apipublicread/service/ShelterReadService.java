package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RedisReadCache.FallbackReason;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterMapTileDto;
import com.safespot.apipublicread.dto.ShelterMapTilesResponse;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.ShelterStatusCache;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
import com.safespot.apipublicread.dto.cache.ShelterStatusCacheDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.repository.EvacuationEntryRepository;
import com.safespot.apipublicread.repository.ShelterRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelterReadService {

    private static final int DEFAULT_NEARBY_LIMIT = 50;
    private static final int MAX_NEARBY_RESULTS = 50;
    private static final int MIN_TILE_ZOOM = 11;
    private static final int MAX_TILE_ZOOM = 16;
    private static final String ALL = "all";
    private static final String ENDPOINT_NEARBY = "/shelters/nearby";
    private static final String ENDPOINT_MAP_TILES = "/shelters/map/tiles";
    private static final String ENDPOINT_DETAIL = "/shelters/{shelterId}";
    private static final String REPOSITORY_SHELTER_STATUS = "shelter_status_repository";

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final RedisReadCache redisReadCache;
    private final FallbackSingleFlight fallbackSingleFlight;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;
    private final MeterRegistry meterRegistry;

    public List<ShelterNearbyItem> findNearby(double lat, double lng, int radiusM, String disasterType, String shelterType, Integer limit) {
        int effectiveLimit = limit != null ? limit : DEFAULT_NEARBY_LIMIT;
        String geoKey = geoKey(disasterType, shelterType);

        RedisReadCache.CacheResult<List<RedisReadCache.GeoSearchHit>> geoResult =
                redisReadCache.geoSearchShelterIds(geoKey, lng, lat, radiusM, effectiveLimit);
        redisReadCache.recordCacheRequest(geoResult.cache(), geoResult.resultLabel());

        if (!geoResult.isHit()) {
            redisReadCache.recordFallback(geoResult.cache(), geoResult.fallbackReason());
            if (geoResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                publishTargetRegenerationIfAllowed("SHELTER_GEO_INDEX", geoKey, CacheRegenerationReason.CACHE_MISS, ENDPOINT_NEARBY);
            }
            return List.of();
        }

        List<RedisReadCache.GeoSearchHit> geoHits = geoResult.value();
        if (geoHits.isEmpty()) {
            return List.of();
        }

        List<Long> shelterIds = geoHits.stream().map(RedisReadCache.GeoSearchHit::shelterId).toList();
        Map<Long, RedisReadCache.CacheResult<ShelterMapItemCacheDto>> mapItems =
                redisReadCache.multiGetShelterMapItems(shelterIds);
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> statusMap =
                redisReadCache.multiGetShelterStatus(shelterIds);

        List<Long> mapItemMissIds = new ArrayList<>();
        List<Long> statusMissIds = new ArrayList<>();
        List<ShelterNearbyItem> items = new ArrayList<>();

        for (RedisReadCache.GeoSearchHit geoHit : geoHits) {
            Long shelterId = geoHit.shelterId();

            RedisReadCache.CacheResult<ShelterMapItemCacheDto> mapItemResult = mapItems.get(shelterId);
            if (mapItemResult == null) {
                mapItemResult = new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_map_item");
            }
            redisReadCache.recordCacheRequest(mapItemResult.cache(), mapItemResult.resultLabel());
            if (!mapItemResult.isHit()) {
                redisReadCache.recordFallback(mapItemResult.cache(), mapItemResult.fallbackReason());
                if (mapItemResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    mapItemMissIds.add(shelterId);
                }
                continue;
            }

            RedisReadCache.CacheResult<ShelterStatusCacheDto> statusResult = statusMap.get(shelterId);
            if (statusResult == null) {
                statusResult = new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_status");
            }
            redisReadCache.recordCacheRequest(statusResult.cache(), statusResult.resultLabel());

            ShelterStatusCache status = toNearbyStatus(statusResult);
            if (!statusResult.isHit()) {
                redisReadCache.recordFallback(statusResult.cache(), statusResult.fallbackReason());
                if (statusResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    statusMissIds.add(shelterId);
                }
            }

            items.add(toNearbyItem(mapItemResult.value(), (int) Math.round(geoHit.distanceM()), status));
        }

        if (!mapItemMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_MAP_ITEMS", "shelter:map:item:batch", mapItemMissIds, CacheRegenerationReason.CACHE_MISS, ENDPOINT_NEARBY);
        }
        if (!statusMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_STATUS", "shelter:status:batch", statusMissIds, CacheRegenerationReason.CACHE_MISS, ENDPOINT_NEARBY);
        }

        return items.stream()
                .sorted(Comparator.comparingInt(ShelterNearbyItem::distanceM))
                .limit(effectiveLimit)
                .toList();
    }

    public ShelterMapTilesResponse findMapTiles(int z, List<String> tiles, String disasterType, String shelterType) {
        if (z < MIN_TILE_ZOOM || z > MAX_TILE_ZOOM) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "z는 11~16 사이여야 합니다.");
        }

        List<TileCoordinate> coordinates = tiles.stream()
                .map(this::parseTileCoordinate)
                .distinct()
                .toList();

        List<String> tileKeys = coordinates.stream()
                .map(tile -> tileKey(z, tile.x(), tile.y(), disasterType, shelterType))
                .toList();
        Map<String, RedisReadCache.CacheResult<List<Long>>> tileResults = redisReadCache.multiGetShelterMapTiles(tileKeys);

        boolean degraded = false;
        boolean tileMiss = false;
        List<Long> allShelterIds = new ArrayList<>();
        Map<String, List<Long>> tileShelterIds = new LinkedHashMap<>();

        for (String key : tileKeys) {
            RedisReadCache.CacheResult<List<Long>> result = tileResults.get(key);
            if (result == null) {
                result = new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_map_tile");
            }
            redisReadCache.recordCacheRequest(result.cache(), result.resultLabel());
            if (!result.isHit()) {
                redisReadCache.recordFallback(result.cache(), result.fallbackReason());
                degraded = true;
                if (result.fallbackReason() == FallbackReason.REDIS_MISS) {
                    tileMiss = true;
                }
                tileShelterIds.put(key, List.of());
                continue;
            }
            List<Long> ids = result.value() != null ? result.value() : List.of();
            tileShelterIds.put(key, ids);
            allShelterIds.addAll(ids);
        }

        if (tileMiss) {
            String suppressKey = "shelter:map:tile:batch:" + z + ":" + dimensionValue(disasterType) + ":" + dimensionValue(shelterType);
            publishTargetRegenerationIfAllowed("SHELTER_MAP_TILES", suppressKey, CacheRegenerationReason.CACHE_MISS, ENDPOINT_MAP_TILES);
        }

        List<Long> distinctShelterIds = allShelterIds.stream().distinct().toList();
        Map<Long, RedisReadCache.CacheResult<ShelterMapItemCacheDto>> mapItems =
                redisReadCache.multiGetShelterMapItems(distinctShelterIds);

        List<Long> missIds = new ArrayList<>();
        Map<Long, ShelterMapItemCacheDto> resolvedItems = new LinkedHashMap<>();
        for (Long shelterId : distinctShelterIds) {
            RedisReadCache.CacheResult<ShelterMapItemCacheDto> result = mapItems.get(shelterId);
            if (result == null) {
                result = new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, "shelter_map_item");
            }
            redisReadCache.recordCacheRequest(result.cache(), result.resultLabel());
            if (result.isHit()) {
                resolvedItems.put(shelterId, result.value());
                continue;
            }
            redisReadCache.recordFallback(result.cache(), result.fallbackReason());
            degraded = true;
            if (result.fallbackReason() == FallbackReason.REDIS_MISS) {
                missIds.add(shelterId);
            }
        }

        if (!missIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_MAP_ITEMS", "shelter:map:item:batch", missIds, CacheRegenerationReason.CACHE_MISS, ENDPOINT_MAP_TILES);
        }

        List<ShelterMapTileDto> responseTiles = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i++) {
            TileCoordinate coordinate = coordinates.get(i);
            String key = tileKeys.get(i);
            List<ShelterMapItemCacheDto> items = tileShelterIds.getOrDefault(key, List.of()).stream()
                    .map(resolvedItems::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            responseTiles.add(new ShelterMapTileDto(z, coordinate.x(), coordinate.y(), items, items.size() != tileShelterIds.getOrDefault(key, List.of()).size() ? Boolean.TRUE : null));
        }

        return new ShelterMapTilesResponse(responseTiles, degraded ? Boolean.TRUE : null);
    }

    public ShelterDetailDto findById(Long shelterId) {
        Shelter shelter = shelterRepository.findById(shelterId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        ShelterStatusCache status = getShelterStatusFromCacheOrRds(shelterId, ENDPOINT_DETAIL, true);
        return toDetailDto(shelter, status);
    }

    private ShelterStatusCache getShelterStatusFromCacheOrRds(Long shelterId, String endpoint, boolean publishRegenerationOnFallback) {
        String key = "shelter:status:" + shelterId;
        RedisReadCache.CacheResult<ShelterStatusCacheDto> cached = redisReadCache.get(key, new TypeReference<>() {});
        return resolveStatusFromCacheResult(shelterId, key, cached, endpoint, publishRegenerationOnFallback);
    }

    private ShelterStatusCache resolveStatusFromCacheResult(
            Long shelterId,
            String key,
            RedisReadCache.CacheResult<ShelterStatusCacheDto> cached,
            String endpoint,
            boolean publishRegenerationOnFallback
    ) {
        redisReadCache.recordCacheRequest(cached.cache(), cached.resultLabel());

        if (cached.isHit()) {
            return new ShelterStatusCache(
                    cached.value().currentOccupancy(),
                    cached.value().availableCapacity(),
                    cached.value().congestionLevel(),
                    cached.value().shelterStatus(),
                    null
            );
        }

        FallbackReason reason = cached.fallbackReason();
        redisReadCache.recordFallback(cached.cache(), reason);

        ShelterStatusCache fallback = fallbackSingleFlight.execute(
                key,
                cached.cache(),
                REPOSITORY_SHELTER_STATUS,
                () -> loadShelterStatusFromRds(shelterId, reason)
        );

        if (publishRegenerationOnFallback && reason != FallbackReason.PARSE_ERROR) {
            meterRegistry.counter("api_read_cache_regen_requested_total",
                    "service", "api-public-read", "endpoint", endpoint).increment();
            meterRegistry.counter("safespot.cache.regeneration.requested",
                    "service", "api-public-read",
                    "cache", cached.cache(),
                    "reason", CacheRegenerationReason.from(reason).value(),
                    "result", "requested").increment();
            if (suppressWindowService.tryPublish(key)) {
                cacheRegenerationPublisher.publish(key, CacheRegenerationReason.from(reason), endpoint);
            } else {
                meterRegistry.counter("api_read_cache_regen_suppressed_total",
                        "service", "api-public-read", "endpoint", endpoint).increment();
                meterRegistry.counter("safespot.cache.regeneration.requested",
                        "service", "api-public-read",
                        "cache", cached.cache(),
                        "reason", CacheRegenerationReason.from(reason).value(),
                        "result", "suppressed").increment();
            }
        }

        return fallback;
    }

    private ShelterStatusCache loadShelterStatusFromRds(Long shelterId, FallbackReason reason) {
        redisReadCache.recordDbFallbackQuery(REPOSITORY_SHELTER_STATUS, reason);
        long start = System.currentTimeMillis();
        try {
            long occupancy = evacuationEntryRepository.countCurrentOccupancy(shelterId);
            Shelter shelter = shelterRepository.findById(shelterId).orElse(null);
            int capacity = shelter != null ? shelter.getCapacity() : 0;
            int available = Math.max(0, capacity - (int) occupancy);
            String congestion = CongestionCalculator.calculate(capacity, (int) occupancy);
            String updatedAt = shelter != null && shelter.getUpdatedAt() != null
                    ? shelter.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
            redisReadCache.recordDbFallbackLatency(REPOSITORY_SHELTER_STATUS, "success", System.currentTimeMillis() - start);
            return new ShelterStatusCache((int) occupancy, available, congestion,
                    shelter != null ? shelter.getShelterStatus() : "OPERATING", updatedAt);
        } catch (RuntimeException e) {
            redisReadCache.recordDbFallbackLatency(REPOSITORY_SHELTER_STATUS, "failure", System.currentTimeMillis() - start);
            throw e;
        }
    }

    private ShelterNearbyItem toNearbyItem(ShelterMapItemCacheDto item, int distanceM, ShelterStatusCache status) {
        int capacityTotal = Math.max(0, status.currentOccupancy() + status.availableCapacity());
        return new ShelterNearbyItem(
                item.shelterId(),
                item.shelterName(),
                item.shelterType(),
                item.disasterType(),
                item.address(),
                item.latitude(),
                item.longitude(),
                distanceM,
                capacityTotal,
                status.currentOccupancy(),
                status.availableCapacity(),
                status.congestionLevel(),
                status.shelterStatus(),
                item.updatedAt()
        );
    }

    private ShelterDetailDto toDetailDto(Shelter s, ShelterStatusCache status) {
        return new ShelterDetailDto(
                s.getShelterId(),
                s.getName(),
                s.getShelterType(),
                s.getDisasterType(),
                s.getAddress(),
                s.getLatitude().doubleValue(),
                s.getLongitude().doubleValue(),
                s.getCapacity(),
                status.currentOccupancy(),
                status.availableCapacity(),
                status.congestionLevel(),
                status.shelterStatus(),
                s.getManager(),
                s.getContact(),
                s.getNote(),
                s.getUpdatedAt() != null ? s.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
        );
    }

    private ShelterStatusCache toNearbyStatus(RedisReadCache.CacheResult<ShelterStatusCacheDto> statusResult) {
        if (statusResult.isHit()) {
            return new ShelterStatusCache(
                    statusResult.value().currentOccupancy(),
                    statusResult.value().availableCapacity(),
                    statusResult.value().congestionLevel(),
                    statusResult.value().shelterStatus(),
                    null
            );
        }
        return new ShelterStatusCache(0, 0, null, null, null);
    }

    private void publishBatchRegenerationIfAllowed(String targetType, String suppressKey, List<Long> missIds,
                                                   CacheRegenerationReason reason, String endpoint) {
        List<Long> distinctIds = missIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return;
        }
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", "api-public-read",
                "cache", cacheFamily(targetType),
                "reason", reason.value(),
                "result", "requested").increment();
        if (suppressWindowService.tryPublish(suppressKey)) {
            cacheRegenerationPublisher.publishBatch(targetType, distinctIds, reason, endpoint);
        } else {
            meterRegistry.counter("safespot.cache.regeneration.requested",
                    "service", "api-public-read",
                    "cache", cacheFamily(targetType),
                    "reason", reason.value(),
                    "result", "suppressed").increment();
        }
    }

    private void publishTargetRegenerationIfAllowed(String targetType, String suppressKey,
                                                    CacheRegenerationReason reason, String endpoint) {
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", "api-public-read",
                "cache", cacheFamily(targetType),
                "reason", reason.value(),
                "result", "requested").increment();
        if (suppressWindowService.tryPublish(suppressKey)) {
            cacheRegenerationPublisher.publishTarget(targetType, reason, endpoint);
        } else {
            meterRegistry.counter("safespot.cache.regeneration.requested",
                    "service", "api-public-read",
                    "cache", cacheFamily(targetType),
                    "reason", reason.value(),
                    "result", "suppressed").increment();
        }
    }

    private String geoKey(String disasterType, String shelterType) {
        return "shelter:geo:seoul:%s:%s".formatted(dimensionValue(disasterType), dimensionValue(shelterType));
    }

    private String tileKey(int z, int x, int y, String disasterType, String shelterType) {
        return "shelter:map:tile:%d:%d:%d:%s:%s".formatted(z, x, y, dimensionValue(disasterType), dimensionValue(shelterType));
    }

    private String dimensionValue(String value) {
        return value == null || value.isBlank() ? ALL : value;
    }

    private String cacheFamily(String targetType) {
        return switch (targetType) {
            case "SHELTER_STATUS" -> "shelter_status";
            case "SHELTER_MAP_ITEMS" -> "shelter_map_item";
            case "SHELTER_GEO_INDEX" -> "shelter_geo_index";
            case "SHELTER_MAP_TILES" -> "shelter_map_tile";
            default -> "unknown";
        };
    }

    private TileCoordinate parseTileCoordinate(String rawTile) {
        String[] parts = rawTile.split(":");
        if (parts.length != 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }
        try {
            return new TileCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }
    }

    private record TileCoordinate(int x, int y) {}
}
