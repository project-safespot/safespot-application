package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.repository.AirQualityLogRepository;
import com.safespot.apipublicread.repository.WeatherLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnvironmentReadService {

    private static final String ENDPOINT_WEATHER = "/weather-alerts";
    private static final String ENDPOINT_AIR = "/air-quality";

    static final String WEATHER_KEY = "environment:weather:seoul";
    static final String AIR_KEY = "environment:air-quality:seoul";

    private final WeatherLogRepository weatherLogRepository;
    private final AirQualityLogRepository airQualityLogRepository;
    private final RedisReadCache redisReadCache;
    private final RegionToGridResolver regionToGridResolver;
    private final SuppressWindowService suppressWindowService;
    private final CacheRegenerationPublisher cacheRegenerationPublisher;
    private final MeterRegistry meterRegistry;

    public WeatherAlertDto findWeather(String region, Integer nx, Integer ny) {
        if (nx != null && ny != null) {
            return findWeatherByGrid(region, nx, ny);
        }
        if (region != null) {
            return findWeatherByRegion(region);
        }
        throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, nx, ny 중 최소 1개는 필요합니다.");
    }

    private WeatherAlertDto findWeatherByGrid(String region, int nx, int ny) {
        RedisReadCache.CacheResult<WeatherAlertDto> cached = redisReadCache.get(WEATHER_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(ENDPOINT_WEATHER, cached.resultLabel());
        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_WEATHER, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_WEATHER);
        requestRegeneration(WEATHER_KEY, ENDPOINT_WEATHER, cached.fallbackReason());

        long start = System.currentTimeMillis();
        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(nx, ny).orElse(null);
        redisReadCache.recordDbFallbackLatency(ENDPOINT_WEATHER, System.currentTimeMillis() - start);
        if (log == null) return null;
        return toWeatherDto(region, log);
    }

    private WeatherAlertDto findWeatherByRegion(String region) {
        int[] grid = regionToGridResolver.resolve(region)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_REGION,
                        "현재 지원하지 않는 지역입니다: " + region));

        RedisReadCache.CacheResult<WeatherAlertDto> cached = redisReadCache.get(WEATHER_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(ENDPOINT_WEATHER, cached.resultLabel());
        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_WEATHER, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_WEATHER);
        requestRegeneration(WEATHER_KEY, ENDPOINT_WEATHER, cached.fallbackReason());

        long start = System.currentTimeMillis();
        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(grid[0], grid[1]).orElse(null);
        redisReadCache.recordDbFallbackLatency(ENDPOINT_WEATHER, System.currentTimeMillis() - start);
        if (log == null) return null;
        return toWeatherDto(region, log);
    }

    public AirQualityDto findAirQuality(String region, String stationName) {
        if (region == null && stationName == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, stationName 중 최소 1개는 필요합니다.");
        }

        RedisReadCache.CacheResult<AirQualityDto> cached = redisReadCache.get(AIR_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(ENDPOINT_AIR, cached.resultLabel());
        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_AIR, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_AIR);
        requestRegeneration(AIR_KEY, ENDPOINT_AIR, cached.fallbackReason());

        long start = System.currentTimeMillis();
        AirQualityLog log = airQualityLogRepository.findLatest().orElse(null);
        redisReadCache.recordDbFallbackLatency(ENDPOINT_AIR, System.currentTimeMillis() - start);
        if (log == null) return null;
        return new AirQualityDto(
                log.getStationName(),
                log.getKhaiValue(),
                log.getKhaiGrade(),
                log.getMeasuredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
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

    private WeatherAlertDto toWeatherDto(String region, WeatherLog log) {
        return new WeatherAlertDto(
                region,
                log.getNx(),
                log.getNy(),
                log.getTmp() != null ? log.getTmp().doubleValue() : null,
                log.getSky() != null ? log.getSky() : "알수없음",
                log.getForecastDt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }
}
