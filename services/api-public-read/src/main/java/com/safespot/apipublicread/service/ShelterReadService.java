package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RedisReadCache.FallbackReason;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.Shelter;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.ShelterStatusCache;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
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
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShelterReadService {

    private static final int MAX_NEARBY_RESULTS = 50;
    private static final String ENDPOINT_NEARBY = "/shelters/nearby";
    private static final String ENDPOINT_DETAIL = "/shelters/{shelterId}";

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final RedisReadCache redisReadCache;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;

    public List<ShelterNearbyItem> findNearby(double lat, double lng, int radiusM, String disasterType) {
        double latDelta = CongestionCalculator.metersToDegreeLat(radiusM);
        double lngDelta = CongestionCalculator.metersToDegreeeLng(radiusM, lat);

        List<Shelter> shelters = shelterRepository.findByBoundingBoxAndDisasterType(
                BigDecimal.valueOf(lat - latDelta),
                BigDecimal.valueOf(lat + latDelta),
                BigDecimal.valueOf(lng - lngDelta),
                BigDecimal.valueOf(lng + lngDelta),
                disasterType
        );

        return shelters.stream()
                .map(s -> {
                    int dist = CongestionCalculator.distanceMeters(
                            lat, lng,
                            s.getLatitude().doubleValue(),
                            s.getLongitude().doubleValue()
                    );
                    if (dist > radiusM) return null;

                    ShelterStatusCache status = getShelterStatusFromCacheOrRds(s.getShelterId(), ENDPOINT_NEARBY);
                    return toNearbyItem(s, dist, status);
                })
                .filter(item -> item != null)
                .sorted(Comparator.comparingInt(ShelterNearbyItem::distanceM))
                .limit(MAX_NEARBY_RESULTS)
                .toList();
    }

    public ShelterDetailDto findById(Long shelterId) {
        Shelter shelter = shelterRepository.findById(shelterId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        ShelterStatusCache status = getShelterStatusFromCacheOrRds(shelterId, ENDPOINT_DETAIL);
        return toDetailDto(shelter, status);
    }

    private ShelterStatusCache getShelterStatusFromCacheOrRds(Long shelterId, String endpoint) {
        String key = "shelter:status:" + shelterId;
        RedisReadCache.CacheResult<ShelterStatusCache> cached = redisReadCache.get(key, new TypeReference<>() {});

        if (cached.isHit()) {
            return cached.value();
        }

        FallbackReason reason = cached.fallbackReason();
        redisReadCache.recordFallback(endpoint, reason);
        redisReadCache.recordDbFallbackQuery(endpoint);

        long occupancy = evacuationEntryRepository.countCurrentOccupancy(shelterId);
        Shelter shelter = shelterRepository.findById(shelterId).orElse(null);
        int capacity = shelter != null ? shelter.getCapacity() : 0;
        int available = Math.max(0, capacity - (int) occupancy);
        String congestion = CongestionCalculator.calculate(capacity, (int) occupancy);
        String updatedAt = shelter != null && shelter.getUpdatedAt() != null
                ? shelter.getUpdatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;

        if (suppressWindowService.tryPublish(key)) {
            cacheRegenerationPublisher.publish(key);
        }

        return new ShelterStatusCache((int) occupancy, available, congestion,
                shelter != null ? shelter.getShelterStatus() : "운영중", updatedAt);
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
