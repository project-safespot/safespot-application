package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.domain.DisasterAlertDetail;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.repository.DisasterAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisasterAlertReadService {

    private static final String ENDPOINT_LIST = "/disaster-alerts";
    private static final String ENDPOINT_LATEST = "/disasters/{disasterType}/latest";

    static final String LIST_KEY = "disaster:messages:list:seoul";
    static final String DETAIL_KEY_PREFIX = "disaster:detail:";

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisReadCache redisReadCache;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;

    public List<DisasterAlertItem> findAlerts(String region, String disasterType) {
        RedisReadCache.CacheResult<List<DisasterAlertItem>> cached =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        if (cached.isHit()) {
            return filterItems(cached.value(), region, disasterType);
        }

        redisReadCache.recordFallback(ENDPOINT_LIST, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LIST);
        if (suppressWindowService.tryPublish(LIST_KEY)) {
            cacheRegenerationPublisher.publish(LIST_KEY);
        }

        return disasterAlertRepository.findAlerts(region, disasterType)
                .stream().map(this::toItem).toList();
    }

    public DisasterLatestDto findLatest(String disasterType, String region) {
        RedisReadCache.CacheResult<List<DisasterAlertItem>> listResult =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        if (listResult.isHit()) {
            DisasterAlertItem match = filterByType(listResult.value(), disasterType);
            if (match != null) {
                return resolveDetail(match, disasterType, region);
            }
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        redisReadCache.recordFallback(ENDPOINT_LATEST, listResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
        if (suppressWindowService.tryPublish(LIST_KEY)) {
            cacheRegenerationPublisher.publish(LIST_KEY);
        }

        return disasterAlertRepository.findLatest(disasterType, region)
                .map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DisasterLatestDto resolveDetail(DisasterAlertItem item, String disasterType, String region) {
        String detailKey = DETAIL_KEY_PREFIX + item.alertId();
        RedisReadCache.CacheResult<DisasterLatestDto> detailResult =
                redisReadCache.get(detailKey, new TypeReference<>() {});

        if (detailResult.isHit()) return detailResult.value();

        redisReadCache.recordFallback(ENDPOINT_LATEST, detailResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
        if (suppressWindowService.tryPublish(detailKey)) {
            cacheRegenerationPublisher.publish(detailKey);
        }

        return disasterAlertRepository.findLatest(disasterType, region)
                .map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private static List<DisasterAlertItem> filterItems(List<DisasterAlertItem> items,
                                                        String region, String disasterType) {
        return items.stream()
                .filter(i -> region == null || region.equals(i.region()))
                .filter(i -> disasterType == null || disasterType.equals(i.disasterType()))
                .toList();
    }

    private static DisasterAlertItem filterByType(List<DisasterAlertItem> items, String disasterType) {
        return items.stream()
                .filter(i -> disasterType.equals(i.disasterType()))
                .findFirst()
                .orElse(null);
    }

    private DisasterAlertItem toItem(DisasterAlert a) {
        return new DisasterAlertItem(
                a.getAlertId(),
                a.getDisasterType(),
                a.getRegion(),
                a.getLevel(),
                a.getMessage(),
                a.getIssuedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                a.getExpiredAt() != null ? a.getExpiredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null
        );
    }

    private DisasterLatestDto toLatestDto(DisasterAlert a) {
        DisasterAlertDetail detail = a.getDetail();
        DisasterLatestDto.DisasterDetails details = null;
        if (detail != null) {
            details = new DisasterLatestDto.DisasterDetails(
                    detail.getMagnitude() != null ? detail.getMagnitude().doubleValue() : null,
                    detail.getEpicenter(),
                    detail.getIntensity()
            );
        }
        return new DisasterLatestDto(
                a.getAlertId(),
                a.getDisasterType(),
                a.getRegion(),
                a.getLevel(),
                a.getMessage(),
                a.getIssuedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                a.getExpiredAt() != null ? a.getExpiredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null,
                details
        );
    }
}
