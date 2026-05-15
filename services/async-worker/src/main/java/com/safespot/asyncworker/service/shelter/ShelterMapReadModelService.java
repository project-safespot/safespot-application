package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.redis.ShelterMapItemValue;
import com.safespot.asyncworker.repository.ShelterMapSource;
import com.safespot.asyncworker.repository.ShelterRepository;
import com.safespot.asyncworker.service.shelter.geo.TileCoordinate;
import com.safespot.asyncworker.service.shelter.geo.TileCoordinateCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Profile({"cache-worker", "async-worker"})
@Slf4j
@Service
@RequiredArgsConstructor
public class ShelterMapReadModelService {

    static final int MAP_ITEM_SCHEMA_VERSION = 1;
    static final int MIN_ZOOM = 11;
    static final int MAX_ZOOM = 16;
    static final String ALL = "all";
    static final List<String> DISASTER_TYPES = List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE");
    static final List<String> SHELTER_TYPES = List.of("DESIGNATED", "TEMPORARY", "WIDE");

    private final ShelterRepository shelterRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuildAllMapItems() {
        List<ShelterMapSource> sources = shelterRepository.findAllForMapReadModel();
        int writtenCount = 0;
        for (ShelterMapSource source : sources) {
            NormalizedShelter normalized = normalize(source);
            if (normalized == null) {
                continue;
            }
            cacheWriter.setShelterMapItem(normalized.shelterId(), normalized.toMapItemValue());
            writtenCount++;
        }
        log.info("Shelter map items rebuilt (all): sourceCount={}, writtenCount={}", sources.size(), writtenCount);
    }

    public void rebuildMapItems(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) {
            throw new EventProcessingException("SHELTER_MAP_ITEMS requires non-empty targetIds");
        }

        List<Long> distinctIds = shelterIds.stream().distinct().toList();
        List<ShelterMapSource> sources = shelterRepository.findByIdsForMapItems(distinctIds);
        for (ShelterMapSource source : sources) {
            NormalizedShelter normalized = normalize(source);
            if (normalized == null) {
                continue;
            }
            cacheWriter.setShelterMapItem(normalized.shelterId(), normalized.toMapItemValue());
        }
        log.info("Shelter map items rebuilt: requestedCount={}, foundCount={}", distinctIds.size(), sources.size());
    }

    public void rebuildGeoIndex() {
        List<ShelterMapSource> sources = shelterRepository.findAllForMapReadModel();
        List<NormalizedShelter> normalizedShelters = normalizeAll(sources);
        String runId = newRunId();
        Map<String, String> geoKeySwaps = geoKeySwaps(runId);
        List<String> tempKeys = new ArrayList<>(geoKeySwaps.keySet());
        Collection<String> populatedTempKeys = new HashSet<>();

        try {
            cacheWriter.deleteKeys(tempKeys);
            for (NormalizedShelter shelter : normalizedShelters) {
                for (GeoKeyTarget target : geoTargetsFor(geoKeySwaps, shelter.disasterType(), shelter.shelterType())) {
                    populatedTempKeys.add(target.tempKey());
                    cacheWriter.geoAddShelterToKey(
                        target.tempKey(),
                        shelter.longitude(),
                        shelter.latitude(),
                        shelter.shelterId()
                    );
                }
            }
            List<String> emptyActiveKeys = geoKeySwaps.entrySet().stream()
                .filter(entry -> !populatedTempKeys.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
            cacheWriter.deleteKeys(emptyActiveKeys);
            for (Map.Entry<String, String> entry : geoKeySwaps.entrySet()) {
                if (!populatedTempKeys.contains(entry.getKey())) {
                    continue;
                }
                cacheWriter.renameKey(entry.getKey(), entry.getValue());
            }
            log.info("Shelter GEO index rebuilt with temp swap: runId={}, count={}, keyCount={}",
                runId, normalizedShelters.size(), populatedTempKeys.size());
        } catch (RuntimeException e) {
            cleanupTempKeys(tempKeys, "geo", runId);
            throw e;
        }
    }

    public void rebuildMapTiles() {
        List<ShelterMapSource> sources = shelterRepository.findAllForMapReadModel();
        List<NormalizedShelter> normalizedShelters = normalizeAll(sources);
        String runId = newRunId();

        Map<TileBucketKey, TreeSet<Long>> tileBuckets = normalizedShelters.stream()
            .flatMap(shelter -> tileBucketEntries(shelter).stream())
            .collect(Collectors.groupingBy(
                TileBucketEntry::key,
                Collectors.mapping(TileBucketEntry::shelterId, Collectors.toCollection(TreeSet::new))
            ));

        Map<String, String> tileKeySwaps = tileBuckets.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> tempTileKey(runId, entry.getKey()),
                entry -> activeTileKey(entry.getKey()),
                (left, right) -> left,
                LinkedHashMap::new
            ));
        List<String> tempKeys = new ArrayList<>(tileKeySwaps.keySet());

        try {
            cacheWriter.deleteKeys(tempKeys);
            tileBuckets.forEach((key, shelterIds) -> cacheWriter.setShelterMapTileToKey(
                tempTileKey(runId, key),
                new ArrayList<>(shelterIds)
            ));
            cacheWriter.deleteByPattern("shelter:map:tile:*");
            for (Map.Entry<String, String> entry : tileKeySwaps.entrySet()) {
                cacheWriter.renameKey(entry.getKey(), entry.getValue());
            }
            log.info("Shelter map tiles rebuilt with temp swap: runId={}, shelterCount={}, tileCount={}",
                runId, normalizedShelters.size(), tileBuckets.size());
        } catch (RuntimeException e) {
            cleanupTempKeys(tempKeys, "tile", runId);
            throw e;
        }
    }

    private List<NormalizedShelter> normalizeAll(List<ShelterMapSource> sources) {
        return sources.stream()
            .map(this::normalize)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private NormalizedShelter normalize(ShelterMapSource source) {
        String disasterType = normalizeDisasterType(source.disasterType());
        String shelterType = normalizeShelterType(source.shelterType());

        if (disasterType == null || shelterType == null) {
            log.warn("Skipping shelter map source with unsupported dimension: shelterId={}, disasterType={}, shelterType={}",
                source.shelterId(), source.disasterType(), source.shelterType());
            return null;
        }
        if (source.latitude() == null || source.longitude() == null || source.updatedAt() == null) {
            log.warn("Skipping shelter map source with missing coordinates or updatedAt: shelterId={}", source.shelterId());
            return null;
        }

        return new NormalizedShelter(
            source.shelterId(),
            source.shelterName(),
            shelterType,
            disasterType,
            source.address(),
            Math.max(0, source.capacityTotal() != null ? source.capacityTotal() : 0),
            source.latitude().doubleValue(),
            source.longitude().doubleValue(),
            source.updatedAt().toString()
        );
    }

    private List<TileBucketEntry> tileBucketEntries(NormalizedShelter shelter) {
        List<TileBucketEntry> entries = new ArrayList<>();
        for (int zoom = MIN_ZOOM; zoom <= MAX_ZOOM; zoom++) {
            TileCoordinate tileCoordinate = TileCoordinateCalculator.from(shelter.latitude(), shelter.longitude(), zoom);
            for (Dimension dimension : dimensionsFor(shelter.disasterType(), shelter.shelterType())) {
                entries.add(new TileBucketEntry(
                    new TileBucketKey(
                        tileCoordinate.z(),
                        tileCoordinate.x(),
                        tileCoordinate.y(),
                        dimension.disasterType(),
                        dimension.shelterType()
                    ),
                    shelter.shelterId()
                ));
            }
        }
        return entries;
    }

    private Collection<String> allGeoKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(RedisKeyConstants.shelterGeo(ALL, ALL));
        for (String disasterType : DISASTER_TYPES) {
            keys.add(RedisKeyConstants.shelterGeo(disasterType, ALL));
        }
        for (String shelterType : SHELTER_TYPES) {
            keys.add(RedisKeyConstants.shelterGeo(ALL, shelterType));
        }
        for (String disasterType : DISASTER_TYPES) {
            for (String shelterType : SHELTER_TYPES) {
                keys.add(RedisKeyConstants.shelterGeo(disasterType, shelterType));
            }
        }
        return keys;
    }

    private List<Dimension> dimensionsFor(String disasterType, String shelterType) {
        return List.of(
            new Dimension(ALL, ALL),
            new Dimension(disasterType, ALL),
            new Dimension(ALL, shelterType),
            new Dimension(disasterType, shelterType)
        );
    }

    private String normalizeDisasterType(String rawDisasterType) {
        if (rawDisasterType == null) {
            return null;
        }
        String upperCase = rawDisasterType.trim().toUpperCase(Locale.ROOT);
        return DISASTER_TYPES.contains(upperCase) ? upperCase : null;
    }

    private String normalizeShelterType(String rawShelterType) {
        if (rawShelterType == null) {
            return null;
        }
        String trimmed = rawShelterType.trim();
        String upperCase = trimmed.toUpperCase(Locale.ROOT);
        if (SHELTER_TYPES.contains(upperCase)) {
            return upperCase;
        }
        return switch (trimmed) {
            case "지정대피소" -> "DESIGNATED";
            case "임시대피소" -> "TEMPORARY";
            default -> null;
        };
    }

    private Map<String, String> geoKeySwaps(String runId) {
        return allGeoKeys().stream()
            .collect(Collectors.toMap(
                activeKey -> tempGeoKey(runId, activeKey),
                activeKey -> activeKey,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private List<GeoKeyTarget> geoTargetsFor(Map<String, String> geoKeySwaps, String disasterType, String shelterType) {
        return dimensionsFor(disasterType, shelterType).stream()
            .map(dimension -> {
                String activeKey = RedisKeyConstants.shelterGeo(dimension.disasterType(), dimension.shelterType());
                return new GeoKeyTarget(geoKeySwaps.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(activeKey))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(), activeKey);
            })
            .toList();
    }

    private String tempGeoKey(String runId, String activeKey) {
        String suffix = activeKey.substring("shelter:geo:".length());
        return "shelter:geo:tmp:" + runId + ":" + suffix;
    }

    private String activeTileKey(TileBucketKey key) {
        return RedisKeyConstants.shelterMapTile(
            key.z(), key.x(), key.y(), key.disasterType(), key.shelterType()
        );
    }

    private String tempTileKey(String runId, TileBucketKey key) {
        return "shelter:map:tmp:tile:" + runId + ":" +
            key.z() + ":" + key.x() + ":" + key.y() + ":" + key.disasterType() + ":" + key.shelterType();
    }

    private void cleanupTempKeys(Collection<String> tempKeys, String family, String runId) {
        try {
            cacheWriter.deleteKeys(tempKeys);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Failed to clean up temporary {} keys after rebuild failure: runId={}, keyCount={}",
                family, runId, tempKeys.size(), cleanupFailure);
        }
    }

    private String newRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Dimension(String disasterType, String shelterType) {}

    private record GeoKeyTarget(String tempKey, String activeKey) {}

    private record TileBucketKey(int z, int x, int y, String disasterType, String shelterType) {}

    private record TileBucketEntry(TileBucketKey key, Long shelterId) {}

    private record NormalizedShelter(
        Long shelterId,
        String shelterName,
        String shelterType,
        String disasterType,
        String address,
        int capacityTotal,
        double latitude,
        double longitude,
        String updatedAt
    ) {
        private ShelterMapItemValue toMapItemValue() {
            return new ShelterMapItemValue(
                MAP_ITEM_SCHEMA_VERSION,
                shelterId,
                shelterName,
                shelterType,
                disasterType,
                address,
                capacityTotal,
                latitude,
                longitude,
                updatedAt
            );
        }
    }
}
