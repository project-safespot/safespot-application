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

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisReadCache redisReadCache;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;

    public List<DisasterAlertItem> findAlerts(String region, String disasterType) {
        String key = buildListKey(region, disasterType);
        RedisReadCache.CacheResult<List<DisasterAlertItem>> cached =
                redisReadCache.get(key, new TypeReference<>() {});

        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_LIST, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LIST);

        List<DisasterAlertItem> result = disasterAlertRepository.findAlerts(region, disasterType)
                .stream().map(this::toItem).toList();

        if (suppressWindowService.tryPublish(key)) {
            cacheRegenerationPublisher.publish(key);
        }

        return result;
    }

    public DisasterLatestDto findLatest(String disasterType, String region) {
        String pointerKey = buildLatestPointerKey(disasterType, region);

        RedisReadCache.CacheResult<Long> pointerResult = redisReadCache.get(pointerKey, new TypeReference<>() {});

        boolean detailMissed = false;

        if (pointerResult.isHit()) {
            String detailKey = buildDetailKey(pointerResult.value());
            RedisReadCache.CacheResult<DisasterLatestDto> detailResult =
                    redisReadCache.get(detailKey, new TypeReference<>() {});
            if (detailResult.isHit()) return detailResult.value();

            redisReadCache.recordFallback(ENDPOINT_LATEST, detailResult.fallbackReason());
            redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
            detailMissed = true;
        } else {
            redisReadCache.recordFallback(ENDPOINT_LATEST, pointerResult.fallbackReason());
            redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
        }

        DisasterAlert alert = disasterAlertRepository.findLatest(disasterType, region)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        DisasterLatestDto result = toLatestDto(alert);

        // pointer miss → worker must rebuild the pointer (publish pointer key)
        // detail miss  → worker must rebuild the detail entry (publish detail key for the actual latest alertId)
        String publishKey = detailMissed ? buildDetailKey(alert.getAlertId()) : pointerKey;
        if (suppressWindowService.tryPublish(publishKey)) {
            cacheRegenerationPublisher.publish(publishKey);
        }

        return result;
    }

    private static String buildLatestPointerKey(String disasterType, String region) {
        return "disaster:latest:" + disasterType + ":" + region;
    }

    private static String buildDetailKey(long alertId) {
        return "disaster:detail:" + alertId;
    }

    private String buildListKey(String region, String disasterType) {
        String r = region != null ? region : "ALL";
        String d = disasterType != null ? disasterType : "ALL";
        return "disaster:alert:list:" + r + ":" + d;
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
