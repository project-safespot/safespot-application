package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.DistributedFallbackGuard;
import com.safespot.apipublicread.cache.FallbackControlProperties;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.PublicReadMetricRecorder;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelterReadService {

    private static final int DEFAULT_NEARBY_LIMIT = 50;
    private static final int MIN_TILE_ZOOM = 11;
    private static final int MAX_TILE_ZOOM = 16;
    private static final String ALL = "all";
    private static final String CACHE_SHELTER_STATUS = "shelter_status";
    private static final String CACHE_SHELTER_MAP_ITEM = "shelter_map_item";
    private static final String CACHE_SHELTER_MAP_TILE = "shelter_map_tile";
    private static final String ENDPOINT_NEARBY = "/shelters/nearby";
    private static final String ENDPOINT_MAP_TILES = "/shelters/map/tiles";
    private static final String ENDPOINT_DETAIL = "/shelters/{shelterId}";
    private static final String REPOSITORY_SHELTER_STATUS = "shelter_status_repository";
    private static final String REPOSITORY_SHELTER_MAP_ITEM = "shelter_map_item_repository";
    private static final String REPOSITORY_SHELTER_MAP_TILE = "shelter_map_tile_repository";

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final RedisReadCache redisReadCache;
    private final FallbackSingleFlight fallbackSingleFlight;
    private final DistributedFallbackGuard distributedFallbackGuard;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;
    private final PublicReadMetricRecorder metricRecorder;
    private final FallbackControlProperties fallbackControlProperties;

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

        List<Long> shelterIds = geoHits.stream().map(RedisReadCache.GeoSearchHit::shelterId).distinct().toList();
        Map<Long, RedisReadCache.CacheResult<ShelterMapItemCacheDto>> mapItems = redisReadCache.multiGetShelterMapItems(shelterIds);
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> statusMap = redisReadCache.multiGetShelterStatus(shelterIds);
        List<Long> mapItemMissIds = new ArrayList<>();
        List<Long> statusMissIds = new ArrayList<>();

        for (Long shelterId : shelterIds) {
            RedisReadCache.CacheResult<ShelterMapItemCacheDto> mapItemResult = mapItems.getOrDefault(
                    shelterId, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_MAP_ITEM));
            redisReadCache.recordCacheRequest(mapItemResult.cache(), mapItemResult.resultLabel());
            if (!mapItemResult.isHit()) {
                redisReadCache.recordFallback(mapItemResult.cache(), mapItemResult.fallbackReason());
                if (mapItemResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    mapItemMissIds.add(shelterId);
                }
            }

            RedisReadCache.CacheResult<ShelterStatusCacheDto> statusResult = statusMap.getOrDefault(
                    shelterId, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_STATUS));
            redisReadCache.recordCacheRequest(statusResult.cache(), statusResult.resultLabel());
            if (!statusResult.isHit()) {
                redisReadCache.recordFallback(statusResult.cache(), statusResult.fallbackReason());
                if (statusResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    statusMissIds.add(shelterId);
                }
            }
        }

        Map<Long, ShelterMapItemCacheDto> fallbackMapItems = loadShelterMapItemsFromFallback(mapItemMissIds);
        Map<Long, ShelterStatusCache> fallbackStatuses = loadShelterStatusesFromFallback(statusMissIds);

        if (!mapItemMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_MAP_ITEMS", "shelter:map:item:batch", mapItemMissIds,
                    CacheRegenerationReason.CACHE_MISS, ENDPOINT_NEARBY);
        }
        if (!statusMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_STATUS", "shelter:status:batch", statusMissIds,
                    CacheRegenerationReason.CACHE_MISS, ENDPOINT_NEARBY);
        }

        List<ShelterNearbyItem> items = new ArrayList<>();
        for (RedisReadCache.GeoSearchHit geoHit : geoHits) {
            Long shelterId = geoHit.shelterId();
            ShelterMapItemCacheDto mapItem = resolveMapItem(mapItems.get(shelterId), fallbackMapItems.get(shelterId));
            if (mapItem == null) {
                continue;
            }
            ShelterStatusCache status = resolveStatus(statusMap.get(shelterId), fallbackStatuses.get(shelterId));
            items.add(toNearbyItem(mapItem, (int) Math.round(geoHit.distanceM()), status));
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
        if (tiles == null || tiles.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }

        List<TileCoordinate> coordinates = tiles.stream().map(tile -> parseTileCoordinate(tile, z)).distinct().toList();
        List<String> tileKeys = coordinates.stream()
                .map(tile -> tileKey(z, tile.x(), tile.y(), disasterType, shelterType))
                .toList();
        Map<String, RedisReadCache.CacheResult<List<Long>>> tileResults = redisReadCache.multiGetShelterMapTiles(tileKeys);
        List<String> missTileKeys = new ArrayList<>();
        Map<String, List<Long>> tileShelterIds = new LinkedHashMap<>();
        boolean degraded = false;

        for (String key : tileKeys) {
            RedisReadCache.CacheResult<List<Long>> result = tileResults.getOrDefault(
                    key, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_MAP_TILE));
            redisReadCache.recordCacheRequest(result.cache(), result.resultLabel());
            if (result.isHit()) {
                tileShelterIds.put(key, result.value() != null ? result.value() : List.of());
                continue;
            }
            redisReadCache.recordFallback(result.cache(), result.fallbackReason());
            degraded = true;
            if (result.fallbackReason() == FallbackReason.REDIS_MISS) {
                missTileKeys.add(key);
            } else {
                tileShelterIds.put(key, List.of());
            }
        }

        if (!missTileKeys.isEmpty()) {
            Map<String, RedisReadCache.CacheResult<List<Long>>> staleResults = redisReadCache.multiGetShelterMapTileStale(missTileKeys);
            for (String key : missTileKeys) {
                RedisReadCache.CacheResult<List<Long>> staleResult = staleResults.getOrDefault(
                        key, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_MAP_TILE));
                if (staleResult.isHit()) {
                    tileShelterIds.put(key, staleResult.value() != null ? staleResult.value() : List.of());
                    redisReadCache.recordFallback(CACHE_SHELTER_MAP_TILE, "stale_served");
                    publishCacheKeyRegenerationIfAllowed(key, CACHE_SHELTER_MAP_TILE, CacheRegenerationReason.STALE, ENDPOINT_MAP_TILES);
                    continue;
                }

                TileResolution resolution = resolveTileFallback(key, z, coordinateForKey(key, coordinates, tileKeys), disasterType, shelterType);
                if (resolution.status() == TileResolutionStatus.BLOCKED) {
                    tileShelterIds.put(key, retryTileAfterBackoff(key));
                    continue;
                }
                tileShelterIds.put(key, resolution.shelterIds());
                publishCacheKeyRegenerationIfAllowed(key, CACHE_SHELTER_MAP_TILE, CacheRegenerationReason.CACHE_MISS, ENDPOINT_MAP_TILES);
            }
        }

        List<Long> distinctShelterIds = tileShelterIds.values().stream().flatMap(Collection::stream).distinct().toList();
        Map<Long, RedisReadCache.CacheResult<ShelterMapItemCacheDto>> mapItems = redisReadCache.multiGetShelterMapItems(distinctShelterIds);
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> statusMap = redisReadCache.multiGetShelterStatus(distinctShelterIds);
        List<Long> mapItemMissIds = new ArrayList<>();
        List<Long> statusMissIds = new ArrayList<>();

        for (Long shelterId : distinctShelterIds) {
            RedisReadCache.CacheResult<ShelterMapItemCacheDto> mapItemResult = mapItems.getOrDefault(
                    shelterId, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_MAP_ITEM));
            redisReadCache.recordCacheRequest(mapItemResult.cache(), mapItemResult.resultLabel());
            if (!mapItemResult.isHit()) {
                redisReadCache.recordFallback(mapItemResult.cache(), mapItemResult.fallbackReason());
                degraded = true;
                if (mapItemResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    mapItemMissIds.add(shelterId);
                }
            }

            RedisReadCache.CacheResult<ShelterStatusCacheDto> statusResult = statusMap.getOrDefault(
                    shelterId, new RedisReadCache.CacheResult<>(null, FallbackReason.REDIS_MISS, CACHE_SHELTER_STATUS));
            redisReadCache.recordCacheRequest(statusResult.cache(), statusResult.resultLabel());
            if (!statusResult.isHit()) {
                redisReadCache.recordFallback(statusResult.cache(), statusResult.fallbackReason());
                degraded = true;
                if (statusResult.fallbackReason() == FallbackReason.REDIS_MISS) {
                    statusMissIds.add(shelterId);
                }
            }
        }

        Map<Long, ShelterMapItemCacheDto> fallbackMapItems = loadShelterMapItemsFromFallback(mapItemMissIds);
        Map<Long, ShelterStatusCache> fallbackStatuses = loadShelterStatusesFromFallback(statusMissIds);

        if (!mapItemMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_MAP_ITEMS", "shelter:map:item:batch", mapItemMissIds,
                    CacheRegenerationReason.CACHE_MISS, ENDPOINT_MAP_TILES);
        }
        if (!statusMissIds.isEmpty()) {
            publishBatchRegenerationIfAllowed("SHELTER_STATUS", "shelter:status:batch", statusMissIds,
                    CacheRegenerationReason.CACHE_MISS, ENDPOINT_MAP_TILES);
        }

        Map<Long, ShelterMapItemCacheDto> resolvedItems = new LinkedHashMap<>();
        for (Long shelterId : distinctShelterIds) {
            ShelterMapItemCacheDto mapItem = resolveMapItem(mapItems.get(shelterId), fallbackMapItems.get(shelterId));
            if (mapItem == null) {
                continue;
            }
            ShelterStatusCache status = resolveStatus(statusMap.get(shelterId), fallbackStatuses.get(shelterId));
            resolvedItems.put(shelterId, mergeMapItemWithStatus(mapItem, status));
        }

        List<ShelterMapTileDto> responseTiles = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i++) {
            TileCoordinate coordinate = coordinates.get(i);
            String key = tileKeys.get(i);
            List<Long> ids = tileShelterIds.getOrDefault(key, List.of());
            List<ShelterMapItemCacheDto> items = ids.stream()
                    .map(resolvedItems::get)
                    .filter(Objects::nonNull)
                    .toList();
            responseTiles.add(new ShelterMapTileDto(
                    z,
                    coordinate.x(),
                    coordinate.y(),
                    items,
                    items.size() != ids.size() ? Boolean.TRUE : null
            ));
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
                CACHE_SHELTER_STATUS,
                metricRecorder.region(),
                String.valueOf(shelterId),
                () -> loadShelterStatusFromRds(shelterId, reason)
        );

        if (publishRegenerationOnFallback && reason != FallbackReason.PARSE_ERROR) {
            publishCacheKeyRegenerationIfAllowed(key, cached.cache(), CacheRegenerationReason.from(reason), endpoint);
        }
        return fallback;
    }

    private ShelterStatusCache loadShelterStatusFromRds(Long shelterId, FallbackReason reason) {
        redisReadCache.recordDbFallbackQuery(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS, reason, "leader");
        long start = System.currentTimeMillis();
        try {
            long occupancy = evacuationEntryRepository.countCurrentOccupancy(shelterId);
            Shelter shelter = shelterRepository.findById(shelterId).orElse(null);
            int capacity = shelter != null ? shelter.getCapacity() : 0;
            int available = Math.max(0, capacity - (int) occupancy);
            String congestion = CongestionCalculator.calculate(capacity, (int) occupancy);
            String updatedAt = shelter != null && shelter.getUpdatedAt() != null
                    ? shelter.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS, "success",
                    System.currentTimeMillis() - start);
            return new ShelterStatusCache((int) occupancy, available, congestion,
                    shelter != null ? shelter.getShelterStatus() : "OPERATING", updatedAt);
        } catch (RuntimeException e) {
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS, "failure",
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    private Map<Long, ShelterMapItemCacheDto> loadShelterMapItemsFromFallback(List<Long> missIds) {
        Map<Long, ShelterMapItemCacheDto> resolved = new LinkedHashMap<>();
        for (List<Long> chunk : partition(missIds)) {
            Map<Long, ShelterMapItemCacheDto> chunkResult = fallbackSingleFlight.execute(
                    CACHE_SHELTER_MAP_ITEM,
                    metricRecorder.region(),
                    batchLogicalKey(chunk),
                    () -> loadShelterMapItemsChunk(chunk)
            );
            resolved.putAll(chunkResult);
        }
        return resolved;
    }

    private Map<Long, ShelterStatusCache> loadShelterStatusesFromFallback(List<Long> missIds) {
        Map<Long, ShelterStatusCache> resolved = new LinkedHashMap<>();
        for (List<Long> chunk : partition(missIds)) {
            Map<Long, ShelterStatusCache> chunkResult = fallbackSingleFlight.execute(
                    CACHE_SHELTER_STATUS,
                    metricRecorder.region(),
                    batchLogicalKey(chunk),
                    () -> loadShelterStatusChunk(chunk)
            );
            resolved.putAll(chunkResult);
        }
        return resolved;
    }

    private Map<Long, ShelterMapItemCacheDto> loadShelterMapItemsChunk(List<Long> chunk) {
        DistributedFallbackGuard.Decision decision = distributedFallbackGuard.tryAcquire(
                CACHE_SHELTER_MAP_ITEM,
                metricRecorder.region(),
                batchLogicalKey(chunk),
                fallbackControlProperties.lockTtl(CACHE_SHELTER_MAP_ITEM)
        );
        if (decision != DistributedFallbackGuard.Decision.LEADER) {
            return Map.of();
        }

        redisReadCache.recordDbFallbackQuery(CACHE_SHELTER_MAP_ITEM, REPOSITORY_SHELTER_MAP_ITEM, "cache_miss", "leader");
        long start = System.currentTimeMillis();
        try {
            Map<Long, ShelterMapItemCacheDto> result = shelterRepository.findAllById(chunk).stream()
                    .collect(LinkedHashMap::new, (map, shelter) -> map.put(shelter.getShelterId(), toMapItem(shelter)), LinkedHashMap::putAll);
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_MAP_ITEM, REPOSITORY_SHELTER_MAP_ITEM,
                    result.isEmpty() ? "empty" : "success", System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_MAP_ITEM, REPOSITORY_SHELTER_MAP_ITEM, "failure",
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    private Map<Long, ShelterStatusCache> loadShelterStatusChunk(List<Long> chunk) {
        DistributedFallbackGuard.Decision decision = distributedFallbackGuard.tryAcquire(
                CACHE_SHELTER_STATUS,
                metricRecorder.region(),
                batchLogicalKey(chunk),
                fallbackControlProperties.lockTtl(CACHE_SHELTER_STATUS)
        );
        if (decision != DistributedFallbackGuard.Decision.LEADER) {
            return Map.of();
        }

        redisReadCache.recordDbFallbackQuery(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS, "cache_miss", "leader");
        long start = System.currentTimeMillis();
        try {
            Map<Long, Shelter> shelters = shelterRepository.findAllById(chunk).stream()
                    .collect(LinkedHashMap::new, (map, shelter) -> map.put(shelter.getShelterId(), shelter), LinkedHashMap::putAll);
            Map<Long, Long> occupancyByShelterId = new LinkedHashMap<>();
            evacuationEntryRepository.countCurrentOccupancyByShelterIds(chunk)
                    .forEach(row -> occupancyByShelterId.put(row.getShelterId(), row.getCurrentOccupancy()));

            Map<Long, ShelterStatusCache> result = new LinkedHashMap<>();
            for (Long shelterId : chunk) {
                Shelter shelter = shelters.get(shelterId);
                if (shelter == null) {
                    continue;
                }
                long occupancy = occupancyByShelterId.getOrDefault(shelterId, 0L);
                int available = Math.max(0, shelter.getCapacity() - (int) occupancy);
                result.put(shelterId, new ShelterStatusCache(
                        (int) occupancy,
                        available,
                        CongestionCalculator.calculate(shelter.getCapacity(), (int) occupancy),
                        shelter.getShelterStatus(),
                        shelter.getUpdatedAt() != null ? shelter.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
                ));
            }
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS,
                    result.isEmpty() ? "empty" : "success", System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_STATUS, REPOSITORY_SHELTER_STATUS, "failure",
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    private TileResolution resolveTileFallback(String tileKey, int z, TileCoordinate coordinate, String disasterType, String shelterType) {
        if (suppressWindowService.isDbFallbackSuppressed(tileKey)) {
            redisReadCache.recordFallback(CACHE_SHELTER_MAP_TILE, "negative_cached");
            return new TileResolution(List.of(), TileResolutionStatus.EMPTY);
        }

        try {
            return fallbackSingleFlight.execute(
                    CACHE_SHELTER_MAP_TILE,
                    metricRecorder.region(),
                    tileKey,
                    () -> loadTileChunk(tileKey, z, coordinate, disasterType, shelterType)
            );
        } catch (FallbackSingleFlight.JoinTimeoutException e) {
            redisReadCache.recordFallback(CACHE_SHELTER_MAP_TILE, "singleflight_timeout");
            return new TileResolution(List.of(), TileResolutionStatus.BLOCKED);
        }
    }

    private TileResolution loadTileChunk(String tileKey, int z, TileCoordinate coordinate, String disasterType, String shelterType) {
        DistributedFallbackGuard.Decision decision = distributedFallbackGuard.tryAcquire(
                CACHE_SHELTER_MAP_TILE,
                metricRecorder.region(),
                tileKey,
                fallbackControlProperties.lockTtl(CACHE_SHELTER_MAP_TILE)
        );
        if (decision != DistributedFallbackGuard.Decision.LEADER) {
            return new TileResolution(List.of(), TileResolutionStatus.BLOCKED);
        }

        redisReadCache.recordDbFallbackQuery(CACHE_SHELTER_MAP_TILE, REPOSITORY_SHELTER_MAP_TILE, "cache_miss", "leader");
        long start = System.currentTimeMillis();
        try {
            TileBounds bounds = tileBounds(z, coordinate.x(), coordinate.y());
            String disasterFilter = filterDimension(disasterType);
            String shelterFilter = filterDimension(shelterType);
            List<Long> shelterIds = shelterRepository.findByBoundingBoxAndFilters(
                            BigDecimal.valueOf(bounds.latMin()),
                            BigDecimal.valueOf(bounds.latMax()),
                            BigDecimal.valueOf(bounds.lngMin()),
                            BigDecimal.valueOf(bounds.lngMax()),
                            disasterFilter,
                            shelterFilter
                    ).stream()
                    .map(Shelter::getShelterId)
                    .distinct()
                    .toList();
            if (shelterIds.isEmpty()) {
                suppressWindowService.markDbFallbackSuppressed(tileKey, fallbackControlProperties.getShelterMapTileNegativeTtl());
            }
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_MAP_TILE, REPOSITORY_SHELTER_MAP_TILE,
                    shelterIds.isEmpty() ? "empty" : "success", System.currentTimeMillis() - start);
            return new TileResolution(shelterIds, TileResolutionStatus.EMPTY);
        } catch (RuntimeException e) {
            redisReadCache.recordDbFallbackLatency(CACHE_SHELTER_MAP_TILE, REPOSITORY_SHELTER_MAP_TILE, "failure",
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    private List<Long> retryTileAfterBackoff(String tileKey) {
        sleep(fallbackControlProperties.getFollowerBackoff().toMillis());
        RedisReadCache.CacheResult<List<Long>> fresh = redisReadCache.get(tileKey, new TypeReference<>() {});
        if (fresh.isHit()) {
            return fresh.value() != null ? fresh.value() : List.of();
        }
        RedisReadCache.CacheResult<List<Long>> stale = redisReadCache.get("stale:" + tileKey, new TypeReference<>() {});
        if (stale.isHit()) {
            redisReadCache.recordFallback(CACHE_SHELTER_MAP_TILE, "stale_served");
            return stale.value() != null ? stale.value() : List.of();
        }
        redisReadCache.recordFallback(CACHE_SHELTER_MAP_TILE, "degraded_empty");
        return List.of();
    }

    private ShelterMapItemCacheDto resolveMapItem(RedisReadCache.CacheResult<ShelterMapItemCacheDto> cached, ShelterMapItemCacheDto fallback) {
        if (cached != null && cached.isHit()) {
            return cached.value();
        }
        return fallback;
    }

    private ShelterStatusCache resolveStatus(RedisReadCache.CacheResult<ShelterStatusCacheDto> cached, ShelterStatusCache fallback) {
        if (cached != null && cached.isHit()) {
            return new ShelterStatusCache(
                    cached.value().currentOccupancy(),
                    cached.value().availableCapacity(),
                    cached.value().congestionLevel(),
                    cached.value().shelterStatus(),
                    null
            );
        }
        return fallback != null ? fallback : emptyStatus();
    }

    private ShelterNearbyItem toNearbyItem(ShelterMapItemCacheDto item, int distanceM, ShelterStatusCache status) {
        return new ShelterNearbyItem(
                item.shelterId(),
                item.shelterName(),
                item.shelterType(),
                item.disasterType(),
                item.address(),
                item.latitude(),
                item.longitude(),
                distanceM,
                item.capacityTotal(),
                status.currentOccupancy(),
                status.availableCapacity(),
                status.congestionLevel(),
                status.shelterStatus(),
                item.updatedAt()
        );
    }

    private ShelterMapItemCacheDto mergeMapItemWithStatus(ShelterMapItemCacheDto item, ShelterStatusCache status) {
        return new ShelterMapItemCacheDto(
                item.schemaVersion(),
                item.shelterId(),
                item.shelterName(),
                item.shelterType(),
                item.disasterType(),
                item.address(),
                item.capacityTotal(),
                status.currentOccupancy(),
                status.availableCapacity(),
                status.congestionLevel(),
                status.shelterStatus(),
                item.latitude(),
                item.longitude(),
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

    private ShelterMapItemCacheDto toMapItem(Shelter shelter) {
        return new ShelterMapItemCacheDto(
                1,
                shelter.getShelterId(),
                shelter.getName(),
                shelter.getShelterType(),
                shelter.getDisasterType(),
                shelter.getAddress(),
                shelter.getCapacity(),
                null,
                null,
                null,
                null,
                shelter.getLatitude().doubleValue(),
                shelter.getLongitude().doubleValue(),
                shelter.getUpdatedAt() != null ? shelter.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
        );
    }

    private ShelterStatusCache emptyStatus() {
        return new ShelterStatusCache(0, 0, null, null, null);
    }

    private void publishBatchRegenerationIfAllowed(String targetType, String suppressKey, List<Long> missIds,
                                                   CacheRegenerationReason reason, String endpoint) {
        List<Long> distinctIds = missIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return;
        }
        metricRecorder.recordCacheRegeneration(cacheFamily(targetType), reason.value(), "requested");
        if (suppressWindowService.tryPublish(suppressKey, fallbackControlProperties.getRegenerationSuppressTtl())) {
            cacheRegenerationPublisher.publishBatch(targetType, distinctIds, reason, endpoint);
        } else {
            metricRecorder.recordCacheRegeneration(cacheFamily(targetType), reason.value(), "suppressed");
        }
    }

    private void publishTargetRegenerationIfAllowed(String targetType, String suppressKey,
                                                    CacheRegenerationReason reason, String endpoint) {
        metricRecorder.recordCacheRegeneration(cacheFamily(targetType), reason.value(), "requested");
        if (suppressWindowService.tryPublish(suppressKey, fallbackControlProperties.getRegenerationSuppressTtl())) {
            cacheRegenerationPublisher.publishTarget(targetType, reason, endpoint);
        } else {
            metricRecorder.recordCacheRegeneration(cacheFamily(targetType), reason.value(), "suppressed");
        }
    }

    private void publishCacheKeyRegenerationIfAllowed(String cacheKey, String cache, CacheRegenerationReason reason, String endpoint) {
        metricRecorder.recordCacheRegeneration(cache, reason.value(), "requested");
        if (suppressWindowService.tryPublish(cacheKey, fallbackControlProperties.getRegenerationSuppressTtl())) {
            cacheRegenerationPublisher.publish(cacheKey, reason, endpoint);
        } else {
            metricRecorder.recordCacheRegeneration(cache, reason.value(), "suppressed");
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

    private String filterDimension(String value) {
        String dimension = dimensionValue(value);
        return ALL.equals(dimension) ? null : dimension;
    }

    private String cacheFamily(String targetType) {
        return switch (targetType) {
            case "SHELTER_STATUS" -> CACHE_SHELTER_STATUS;
            case "SHELTER_MAP_ITEMS" -> CACHE_SHELTER_MAP_ITEM;
            case "SHELTER_GEO_INDEX" -> "shelter_geo_index";
            case "SHELTER_MAP_TILES" -> CACHE_SHELTER_MAP_TILE;
            default -> "unknown";
        };
    }

    private TileCoordinate parseTileCoordinate(String rawTile, int z) {
        if (rawTile == null || rawTile.trim().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }
        String[] parts = rawTile.trim().split(":");
        if (parts.length != 2) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int maxCoordinate = 1 << z;
            if (x < 0 || y < 0 || x >= maxCoordinate || y >= maxCoordinate) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 좌표 범위를 초과했습니다.");
            }
            return new TileCoordinate(x, y);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "tiles 형식이 올바르지 않습니다.");
        }
    }

    private TileCoordinate coordinateForKey(String key, List<TileCoordinate> coordinates, List<String> tileKeys) {
        for (int i = 0; i < tileKeys.size(); i++) {
            if (tileKeys.get(i).equals(key)) {
                return coordinates.get(i);
            }
        }
        throw new IllegalStateException("Missing tile coordinate for key=" + key);
    }

    private TileBounds tileBounds(int z, int x, int y) {
        double n = Math.pow(2.0, z);
        double lngMin = x / n * 360.0 - 180.0;
        double lngMax = (x + 1) / n * 360.0 - 180.0;
        double latMax = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - (2.0 * y / n)))));
        double latMin = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - (2.0 * (y + 1) / n)))));
        return new TileBounds(latMin, latMax, lngMin, lngMax);
    }

    private List<List<Long>> partition(List<Long> ids) {
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        int chunkSize = Math.max(1, fallbackControlProperties.getBatchChunkSize());
        List<List<Long>> partitions = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i += chunkSize) {
            partitions.add(distinct.subList(i, Math.min(distinct.size(), i + chunkSize)));
        }
        return partitions;
    }

    private String batchLogicalKey(List<Long> ids) {
        return ids.stream().sorted().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("empty");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during follower backoff");
        }
    }

    private record TileCoordinate(int x, int y) {}

    private record TileBounds(double latMin, double latMax, double lngMin, double lngMax) {}

    private enum TileResolutionStatus {
        EMPTY,
        BLOCKED
    }

    private record TileResolution(List<Long> shelterIds, TileResolutionStatus status) {}
}
