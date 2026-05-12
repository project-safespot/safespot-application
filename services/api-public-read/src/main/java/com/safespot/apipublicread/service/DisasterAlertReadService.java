package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.DisasterAlert;
import com.safespot.apipublicread.domain.DisasterAlertDetail;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.dto.cache.DisasterDetailCacheDto;
import com.safespot.apipublicread.dto.cache.DisasterMessageCacheDto;
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
    private static final String REPOSITORY_DISASTER_ALERT = "disaster_alert_repository";

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
        RedisReadCache.CacheResult<List<DisasterMessageCacheDto>> cached =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(cached.cache(), cached.resultLabel());

        if (cached.isHit()) {
            return filterItems(cached.value(), region, disasterType)
                    .stream().map(this::toItem).toList();
        }

        redisReadCache.recordFallback(cached.cache(), cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_DISASTER_ALERT, cached.fallbackReason());
        requestRegeneration(LIST_KEY, cached.cache(), ENDPOINT_LIST, cached.fallbackReason());

        long start = System.currentTimeMillis();
        List<DisasterAlertItem> result = disasterAlertRepository.findAlerts(region, disasterType, FALLBACK_PAGE)
                .stream().map(this::toItem).toList();
        redisReadCache.recordDbFallbackLatency(REPOSITORY_DISASTER_ALERT, "success", System.currentTimeMillis() - start);
        return result;
    }

    public DisasterLatestDto findLatest(String disasterType, String region) {
        RedisReadCache.CacheResult<List<DisasterMessageCacheDto>> listResult =
                redisReadCache.get(LIST_KEY, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(listResult.cache(), listResult.resultLabel());

        if (listResult.isHit()) {
            DisasterMessageCacheDto match = filterByType(listResult.value(), disasterType);
            if (match != null) {
                return resolveDetail(match, disasterType, region);
            }
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        redisReadCache.recordFallback(listResult.cache(), listResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_DISASTER_ALERT, listResult.fallbackReason());
        requestRegeneration(LIST_KEY, listResult.cache(), ENDPOINT_LATEST, listResult.fallbackReason());

        long start = System.currentTimeMillis();
        Optional<DisasterAlert> maybeAlert = disasterAlertRepository.findLatest(disasterType, region);
        redisReadCache.recordDbFallbackLatency(REPOSITORY_DISASTER_ALERT, "success", System.currentTimeMillis() - start);
        return maybeAlert.map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private DisasterLatestDto resolveDetail(DisasterMessageCacheDto item, String disasterType, String region) {
        String detailKey = DETAIL_KEY_PREFIX + item.alertId();
        RedisReadCache.CacheResult<DisasterDetailCacheDto> detailResult =
                redisReadCache.get(detailKey, new TypeReference<>() {});

        redisReadCache.recordCacheRequest(detailResult.cache(), detailResult.resultLabel());

        if (detailResult.isHit()) return toLatestDto(detailResult.value());

        redisReadCache.recordFallback(detailResult.cache(), detailResult.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_DISASTER_ALERT, detailResult.fallbackReason());
        requestRegeneration(detailKey, detailResult.cache(), ENDPOINT_LATEST, detailResult.fallbackReason());

        long start = System.currentTimeMillis();
        Optional<DisasterAlert> maybeAlert = disasterAlertRepository.findLatest(disasterType, region);
        redisReadCache.recordDbFallbackLatency(REPOSITORY_DISASTER_ALERT, "success", System.currentTimeMillis() - start);
        return maybeAlert.map(this::toLatestDto)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private void requestRegeneration(String cacheKey, String cache, String endpoint, RedisReadCache.FallbackReason fallbackReason) {
        if (fallbackReason == RedisReadCache.FallbackReason.PARSE_ERROR) {
            return;
        }
        meterRegistry.counter("api_read_cache_regen_requested_total",
                "service", "api-public-read", "endpoint", endpoint).increment();
        meterRegistry.counter("safespot.cache.regeneration.requested",
                "service", "api-public-read",
                "cache", cache,
                "reason", CacheRegenerationReason.from(fallbackReason).value(),
                "result", "requested").increment();
        if (suppressWindowService.tryPublish(cacheKey)) {
            cacheRegenerationPublisher.publish(cacheKey, CacheRegenerationReason.from(fallbackReason), endpoint);
        } else {
            meterRegistry.counter("api_read_cache_regen_suppressed_total",
                    "service", "api-public-read", "endpoint", endpoint).increment();
            meterRegistry.counter("safespot.cache.regeneration.requested",
                    "service", "api-public-read",
                    "cache", cache,
                    "reason", CacheRegenerationReason.from(fallbackReason).value(),
                    "result", "suppressed").increment();
        }
    }

    private static List<DisasterMessageCacheDto> filterItems(List<DisasterMessageCacheDto> items,
                                                             String region, String disasterType) {
        return items.stream()
                .filter(i -> region == null || region.equals(i.region()))
                .filter(i -> disasterType == null || disasterType.equals(i.disasterType()))
                .toList();
    }

    private static DisasterMessageCacheDto filterByType(List<DisasterMessageCacheDto> items, String disasterType) {
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

    private DisasterAlertItem toItem(DisasterMessageCacheDto item) {
        return new DisasterAlertItem(
                item.alertId(),
                item.disasterType(),
                item.region(),
                item.level(),
                item.message(),
                item.issuedAt(),
                item.expiredAt()
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

    private DisasterLatestDto toLatestDto(DisasterDetailCacheDto value) {
        return new DisasterLatestDto(
                value.alertId(),
                value.disasterType(),
                value.region(),
                value.level(),
                value.message(),
                value.issuedAt(),
                value.expiredAt(),
                null
        );
    }
}
