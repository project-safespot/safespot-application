package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.dto.cache.AirQualityCacheDto;
import com.safespot.apipublicread.dto.cache.WeatherCacheDto;
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
    private static final String REPOSITORY_WEATHER_LOG = "weather_log_repository";
    private static final String REPOSITORY_AIR_QUALITY_LOG = "air_quality_log_repository";

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
        RedisReadCache.CacheResult<WeatherCacheDto> cached = redisReadCache.get(WEATHER_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(cached.cache(), cached.resultLabel());
        if (cached.isHit()) return toWeatherDto(region, cached.value());

        redisReadCache.recordFallback(cached.cache(), cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_WEATHER_LOG, cached.fallbackReason());
        requestRegeneration(WEATHER_KEY, cached.cache(), ENDPOINT_WEATHER, cached.fallbackReason());

        long start = System.currentTimeMillis();
        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(nx, ny).orElse(null);
        redisReadCache.recordDbFallbackLatency(REPOSITORY_WEATHER_LOG, "success", System.currentTimeMillis() - start);
        if (log == null) return null;
        return toWeatherDto(region, log);
    }

    private WeatherAlertDto findWeatherByRegion(String region) {
        int[] grid = regionToGridResolver.resolve(region)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_REGION,
                        "현재 지원하지 않는 지역입니다: " + region));

        RedisReadCache.CacheResult<WeatherCacheDto> cached = redisReadCache.get(WEATHER_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(cached.cache(), cached.resultLabel());
        if (cached.isHit()) return toWeatherDto(region, cached.value());

        redisReadCache.recordFallback(cached.cache(), cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_WEATHER_LOG, cached.fallbackReason());
        requestRegeneration(WEATHER_KEY, cached.cache(), ENDPOINT_WEATHER, cached.fallbackReason());

        long start = System.currentTimeMillis();
        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(grid[0], grid[1]).orElse(null);
        redisReadCache.recordDbFallbackLatency(REPOSITORY_WEATHER_LOG, "success", System.currentTimeMillis() - start);
        if (log == null) return null;
        return toWeatherDto(region, log);
    }

    public AirQualityDto findAirQuality(String region, String stationName) {
        if (region == null && stationName == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, stationName 중 최소 1개는 필요합니다.");
        }

        RedisReadCache.CacheResult<AirQualityCacheDto> cached = redisReadCache.get(AIR_KEY, new TypeReference<>() {});
        redisReadCache.recordCacheRequest(cached.cache(), cached.resultLabel());
        if (cached.isHit()) return toAirQualityDto(cached.value());

        redisReadCache.recordFallback(cached.cache(), cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(REPOSITORY_AIR_QUALITY_LOG, cached.fallbackReason());
        requestRegeneration(AIR_KEY, cached.cache(), ENDPOINT_AIR, cached.fallbackReason());

        long start = System.currentTimeMillis();
        AirQualityLog log = stationName != null
                ? airQualityLogRepository.findLatestByStationName(stationName).orElse(null)
                : airQualityLogRepository.findLatest().orElse(null);
        redisReadCache.recordDbFallbackLatency(REPOSITORY_AIR_QUALITY_LOG, "success", System.currentTimeMillis() - start);
        if (log == null) return null;
        return new AirQualityDto(
                log.getStationName(),
                log.getKhaiValue(),
                log.getKhaiGrade(),
                log.getPm10(),
                log.getPm10Grade(),
                log.getPm25(),
                log.getPm25Grade(),
                log.getO3(),
                log.getO3Grade(),
                log.getMeasuredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
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

    private WeatherAlertDto toWeatherDto(String region, WeatherLog log) {
        return new WeatherAlertDto(
                region,
                log.getNx(),
                log.getNy(),
                log.getTmp() != null ? log.getTmp().doubleValue() : null,
                resolveWeatherCondition(log),
                log.getPty(),
                log.getPcp(),
                log.getWsd(),
                log.getReh(),
                log.getForecastDt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }

    private WeatherAlertDto toWeatherDto(String region, WeatherCacheDto value) {
        return new WeatherAlertDto(
                region,
                value.nx(),
                value.ny(),
                value.temperature(),
                value.weatherCondition(),
                value.precipitationType(),
                value.precipitation(),
                value.windSpeed(),
                value.humidity(),
                value.forecastedAt()
        );
    }

    private AirQualityDto toAirQualityDto(AirQualityCacheDto value) {
        return new AirQualityDto(
                value.stationName(),
                value.aqi(),
                value.grade(),
                value.pm10(),
                value.pm10Grade(),
                value.pm25(),
                value.pm25Grade(),
                value.o3(),
                value.o3Grade(),
                value.measuredAt()
        );
    }

    private String resolveWeatherCondition(WeatherLog log) {
        if (log.getSky() != null) return log.getSky();
        if (log.getPty() != null && !log.getPty().isBlank() && !"없음".equals(log.getPty())) {
            return log.getPty();
        }
        return "관측값";
    }
}
