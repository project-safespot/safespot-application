package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.FallbackSingleFlight;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RedisReadCache.FallbackReason;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.ShelterStatusCache;
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

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelterReadService {

    private static final int MAX_NEARBY_RESULTS = 50;
    private static final String ENDPOINT_NEARBY = "/shelters/nearby";
    private static final String ENDPOINT_DETAIL = "/shelters/{shelterId}";
    private static final String REPOSITORY_SHELTER_STATUS = "shelter_status_repository";

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final RedisReadCache redisReadCache;
    private final FallbackSingleFlight fallbackSingleFlight;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;
    private final MeterRegistry meterRegistry;

    public List<ShelterNearbyItem> findNearby(double lat, double lng, int radiusM, String disasterType) {
        double latDelta = CongestionCalculator.metersToDegreeLat(radiusM);
        double lngDelta = CongestionCalculator.metersToDegreeeLng(radiusM, lat);

        List<Shelter> candidates = shelterRepository.findByBoundingBoxAndDisasterType(
                BigDecimal.valueOf(lat - latDelta),
                BigDecimal.valueOf(lat + latDelta),
                BigDecimal.valueOf(lng - lngDelta),
                BigDecimal.valueOf(lng + lngDelta),
                disasterType
        );

        record ShelterDist(Shelter shelter, int distanceM) {}
        List<ShelterDist> inRadius = candidates.stream()
                .map(s -> new ShelterDist(s, CongestionCalculator.distanceMeters(
                        lat, lng, s.getLatitude().doubleValue(), s.getLongitude().doubleValue())))
                .filter(sd -> sd.distanceM() <= radiusM)
                .toList();

        if (inRadius.isEmpty()) return List.of();

        List<Long> ids = inRadius.stream().map(sd -> sd.shelter().getShelterId()).toList();
        Map<Long, RedisReadCache.CacheResult<ShelterStatusCacheDto>> statusMap =
                redisReadCache.multiGetShelterStatus(ids);

        List<Long> missIds = new ArrayList<>();
        List<ShelterNearbyItem> items = new ArrayList<>();

        for (ShelterDist sd : inRadius) {
            Long shelterId = sd.shelter().getShelterId();
            RedisReadCache.CacheResult<ShelterStatusCacheDto> cacheResult = statusMap.get(shelterId);

            ShelterStatusCache status;
            if (cacheResult != null && cacheResult.isHit()) {
                redisReadCache.recordCacheRequest(cacheResult.cache(), cacheResult.resultLabel());
                status = new ShelterStatusCache(
                        cacheResult.value().currentOccupancy(),
                        cacheResult.value().availableCapacity(),
                        cacheResult.value().congestionLevel(),
                        cacheResult.value().shelterStatus(),
                        null
                );
            } else {
                FallbackReason reason = (cacheResult != null) ? cacheResult.fallbackReason() : FallbackReason.REDIS_MISS;
                String cache = (cacheResult != null) ? cacheResult.cache() : "shelter_status";
                redisReadCache.recordCacheRequest(cache, cacheResult != null ? cacheResult.resultLabel() : "miss");
                redisReadCache.recordFallback(cache, reason);
                if (reason == FallbackReason.REDIS_MISS) {
                    missIds.add(shelterId);
                }
                status = fallbackSingleFlight.execute(
                        "shelter:status:" + shelterId,
                        "shelter_status",
                        REPOSITORY_SHELTER_STATUS,
                        () -> loadShelterStatusFromRds(shelterId, reason)
                );
            }
            items.add(toNearbyItem(sd.shelter(), sd.distanceM(), status));
        }

        if (!missIds.isEmpty()) {
            publishBatchRegenerationIfAllowed(missIds, ENDPOINT_NEARBY);
        }

        return items.stream()
                .sorted(Comparator.comparingInt(ShelterNearbyItem::distanceM))
                .limit(MAX_NEARBY_RESULTS)
                .toList();
    }

    private void publishBatchRegenerationIfAllowed(List<Long> missIds, String endpoint) {
        String batchSuppressKey = "shelter:status:batch";
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", "api-public-read",
                "cache", "shelter_status",
                "reason", CacheRegenerationReason.CACHE_MISS.value(),
                "result", "requested").increment();
        if (suppressWindowService.tryPublish(batchSuppressKey)) {
            cacheRegenerationPublisher.publishBatch(
                    "SHELTER_STATUS", missIds, CacheRegenerationReason.CACHE_MISS, endpoint);
        } else {
            meterRegistry.counter("safespot.cache.regeneration.requested",
                    "service", "api-public-read",
                    "cache", "shelter_status",
                    "reason", CacheRegenerationReason.CACHE_MISS.value(),
                    "result", "suppressed").increment();
        }
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

    private ShelterNearbyItem toNearbyItem(Shelter s, int distanceM, ShelterStatusCache status) {
        return new ShelterNearbyItem(
                s.getShelterId(),
                s.getName(),
                s.getShelterType(),
                s.getDisasterType(),
                s.getAddress(),
                s.getLatitude().doubleValue(),
                s.getLongitude().doubleValue(),
                distanceM,
                s.getCapacity(),
                status.currentOccupancy(),
                status.availableCapacity(),
                status.congestionLevel(),
                status.shelterStatus(),
                s.getUpdatedAt() != null ? s.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
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
}
