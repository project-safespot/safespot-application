package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
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
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnvironmentReadService {

    private static final String ENDPOINT_WEATHER = "/weather-alerts";
    private static final String ENDPOINT_AIR = "/air-quality";
    private static final Duration WEATHER_TTL = Duration.ofMinutes(60);
    private static final Duration AIR_TTL = Duration.ofMinutes(60);

    private static final Map<String, int[]> REGION_TO_GRID = Map.ofEntries(
            Map.entry("서울특별시", new int[]{60, 127}),
            Map.entry("서울", new int[]{60, 127}),
            Map.entry("부산광역시", new int[]{98, 76}),
            Map.entry("부산", new int[]{98, 76}),
            Map.entry("인천광역시", new int[]{55, 124}),
            Map.entry("인천", new int[]{55, 124}),
            Map.entry("대구광역시", new int[]{89, 90}),
            Map.entry("대구", new int[]{89, 90}),
            Map.entry("광주광역시", new int[]{58, 74}),
            Map.entry("광주", new int[]{58, 74}),
            Map.entry("대전광역시", new int[]{67, 100}),
            Map.entry("대전", new int[]{67, 100}),
            Map.entry("울산광역시", new int[]{102, 84}),
            Map.entry("울산", new int[]{102, 84}),
            Map.entry("세종특별자치시", new int[]{66, 103}),
            Map.entry("경기도", new int[]{60, 120}),
            Map.entry("강원도", new int[]{73, 134}),
            Map.entry("충청북도", new int[]{69, 107}),
            Map.entry("충청남도", new int[]{68, 100}),
            Map.entry("전라북도", new int[]{63, 89}),
            Map.entry("전라남도", new int[]{51, 67}),
            Map.entry("경상북도", new int[]{91, 106}),
            Map.entry("경상남도", new int[]{91, 77}),
            Map.entry("제주특별자치도", new int[]{52, 38}),
            Map.entry("제주", new int[]{52, 38})
    );

    private final WeatherLogRepository weatherLogRepository;
    private final AirQualityLogRepository airQualityLogRepository;
    private final RedisReadCache redisReadCache;

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
        int[] grid = REGION_TO_GRID.get(region);
        if (grid == null) return null;

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
