package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.domain.DisasterAlertDetail;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.repository.DisasterAlertRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisasterAlertReadService {

    private static final String ENDPOINT_LIST = "/disaster-alerts";
    private static final String ENDPOINT_LATEST = "/disasters/{disasterType}/latest";

    static final String LIST_KEY = "disaster:messages:list:seoul";
    static final String DETAIL_KEY_PREFIX = "disaster:detail:";

    private static final PageRequest FALLBACK_PAGE =
            PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "issuedAt"));

    private final DisasterAlertRepository disasterAlertRepository;
    private final RedisReadCache redisReadCache;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;
    private final MeterRegistry meterRegistry;

    public List<DisasterAlertItem> findAlerts(String region, String disasterType) {
        RedisReadCache.CacheResult<List<DisasterAlertItem>> cached =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(ENDPOINT_LIST, cached.resultLabel());

        if (cached.isHit()) {
            return filterItems(cached.value(), region, disasterType);
        }

        redisReadCache.recordFallback(ENDPOINT_LIST, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LIST);
        requestRegeneration(LIST_KEY, ENDPOINT_LIST, cached.fallbackReason());

        long start = System.currentTimeMillis();
        List<DisasterAlertItem> result = disasterAlertRepository.findAlerts(region, disasterType, FALLBACK_PAGE)
                .stream().map(this::toItem).toList();
        redisReadCache.recordDbFallbackLatency(ENDPOINT_LIST, System.currentTimeMillis() - start);
        return result;
    }

    public DisasterLatestDto findLatest(String disasterType, String region) {
        RedisReadCache.CacheResult<List<DisasterAlertItem>> listResult =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(ENDPOINT_LATEST, listResult.resultLabel());

        if (listResult.isHit()) {
            DisasterAlertItem match = filterByType(listResult.value(), disasterType);
            if (match != null) {
                return resolveDetail(match, disasterType, region);
            }
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        redisReadCache.recordFallback(ENDPOINT_LATEST, listResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
        requestRegeneration(LIST_KEY, ENDPOINT_LATEST, listResult.fallbackReason());

        long start = System.currentTimeMillis();
        Optional<DisasterAlert> maybeAlert = disasterAlertRepository.findLatest(disasterType, region);
        redisReadCache.recordDbFallbackLatency(ENDPOINT_LATEST, System.currentTimeMillis() - start);
        return maybeAlert.map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DisasterLatestDto resolveDetail(DisasterAlertItem item, String disasterType, String region) {
        String detailKey = DETAIL_KEY_PREFIX + item.alertId();
        RedisReadCache.CacheResult<DisasterLatestDto> detailResult =
                redisReadCache.get(detailKey, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(ENDPOINT_LATEST, detailResult.resultLabel());

        if (detailResult.isHit()) return detailResult.value();

        redisReadCache.recordFallback(ENDPOINT_LATEST, detailResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_LATEST);
        requestRegeneration(detailKey, ENDPOINT_LATEST, detailResult.fallbackReason());

        long start = System.currentTimeMillis();
        Optional<DisasterAlert> maybeAlert = disasterAlertRepository.findLatest(disasterType, region);
        redisReadCache.recordDbFallbackLatency(ENDPOINT_LATEST, System.currentTimeMillis() - start);
        return maybeAlert.map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void requestRegeneration(String cacheKey, String endpoint, RedisReadCache.FallbackReason fallbackReason) {
        meterRegistry.counter("api_read_cache_regen_requested_total",
                "service", "api-public-read", "endpoint", endpoint).increment();
        if (suppressWindowService.tryPublish(cacheKey)) {
            cacheRegenerationPublisher.publish(cacheKey, CacheRegenerationReason.from(fallbackReason), endpoint);
        } else {
            meterRegistry.counter("api_read_cache_regen_suppressed_total",
                    "service", "api-public-read", "endpoint", endpoint).increment();
        }
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
