package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.repository.AirQualityLogRepository;
import com.safespot.apipublicread.repository.WeatherLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnvironmentReadService {

    private static final String ENDPOINT_WEATHER = "/weather-alerts";
    private static final String ENDPOINT_AIR = "/air-quality";
    private static final Duration WEATHER_TTL = Duration.ofMinutes(60);
    private static final Duration AIR_TTL = Duration.ofMinutes(60);

    private final WeatherLogRepository weatherLogRepository;
    private final AirQualityLogRepository airQualityLogRepository;
    private final RedisReadCache redisReadCache;
    private final RegionToGridResolver regionToGridResolver;

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
        String key = "env:weather:" + nx + ":" + ny;
        RedisReadCache.CacheResult<WeatherAlertDto> cached = redisReadCache.get(key, new TypeReference<>() {});

        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_WEATHER, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_WEATHER);

        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(nx, ny).orElse(null);
        if (log == null) return null;

        WeatherAlertDto dto = new WeatherAlertDto(
                region,
                log.getNx(),
                log.getNy(),
                log.getTmp() != null ? log.getTmp().doubleValue() : null,
                buildWeatherCondition(log),
                log.getForecastDt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        redisReadCache.set(key, dto, WEATHER_TTL);
        return dto;
    }

    private WeatherAlertDto findWeatherByRegion(String region) {
        int[] grid = regionToGridResolver.resolve(region)
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_REGION,
                        "현재 지원하지 않는 지역입니다: " + region));

        String key = "env:weather:region:" + region;
        RedisReadCache.CacheResult<WeatherAlertDto> cached = redisReadCache.get(key, new TypeReference<>() {});

        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_WEATHER, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_WEATHER);

        WeatherLog log = weatherLogRepository.findLatestByNxAndNy(grid[0], grid[1]).orElse(null);
        if (log == null) return null;

        WeatherAlertDto dto = new WeatherAlertDto(
                region,
                log.getNx(),
                log.getNy(),
                log.getTmp() != null ? log.getTmp().doubleValue() : null,
                buildWeatherCondition(log),
                log.getForecastDt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        redisReadCache.set(key, dto, WEATHER_TTL);
        return dto;
    }

    public AirQualityDto findAirQuality(String region, String stationName) {
        String resolvedStation = resolveStation(region, stationName);
        if (resolvedStation == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, stationName 중 최소 1개는 필요합니다.");
        }

        String key = "env:air:" + resolvedStation;
        RedisReadCache.CacheResult<AirQualityDto> cached = redisReadCache.get(key, new TypeReference<>() {});

        if (cached.isHit()) return cached.value();

        redisReadCache.recordFallback(ENDPOINT_AIR, cached.fallbackReason());
        redisReadCache.recordDbFallbackQuery(ENDPOINT_AIR);

        AirQualityLog log = airQualityLogRepository.findLatestByStationName(resolvedStation)
                .or(airQualityLogRepository::findLatest)
                .orElse(null);
        if (log == null) return null;

        AirQualityDto dto = new AirQualityDto(
                log.getStationName(),
                log.getKhaiValue(),
                log.getKhaiGrade(),
                log.getMeasuredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );

        redisReadCache.set(key, dto, AIR_TTL);
        return dto;
    }

    private String resolveStation(String region, String stationName) {
        if (stationName != null) return stationName;
        if (region != null) return region;
        return null;
    }

    private String buildWeatherCondition(WeatherLog log) {
        if (log.getSky() != null) return log.getSky();
        return "알수없음";
    }
}
