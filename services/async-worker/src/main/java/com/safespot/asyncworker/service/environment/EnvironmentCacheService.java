package com.safespot.asyncworker.service.environment;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.payload.EnvironmentDataCollectedPayload;
import com.safespot.asyncworker.redis.AirQualityCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.WeatherAlertCacheValue;
import com.safespot.asyncworker.redis.WeatherCacheValue;
import com.safespot.asyncworker.repository.AirQualityLogRecord;
import com.safespot.asyncworker.repository.EnvironmentLogRepository;
import com.safespot.asyncworker.repository.WeatherLogRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Profile("cache-worker")
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentCacheService {

    private static final String WEATHER       = "WEATHER";
    private static final String AIR_QUALITY   = "AIR_QUALITY";
    private static final String WEATHER_ALERT = "WEATHER_ALERT";

    private final EnvironmentLogRepository envLogRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuild(EnvironmentDataCollectedPayload payload) {
        validate(payload);
        switch (payload.collectionType()) {
            case WEATHER       -> rebuildWeather(payload.timeWindow());
            case AIR_QUALITY   -> rebuildAirQuality(payload.timeWindow());
            case WEATHER_ALERT -> rebuildWeatherAlert();
            default -> throw new EventProcessingException(
                "Unsupported collectionType: " + payload.collectionType());
        }
    }

    private void validate(EnvironmentDataCollectedPayload payload) {
        if (payload.collectionType() == null || payload.collectionType().isBlank()) {
            throw new EventProcessingException("EnvironmentDataCollected payload missing collectionType");
        }
        if (payload.timeWindow() == null || payload.timeWindow().isBlank()) {
            throw new EventProcessingException("EnvironmentDataCollected payload missing timeWindow");
        }
    }

    // CacheRegenerationRequested 처리용 — weather_alert_log is not in MVP schema; set no_data placeholder
    public void rebuildWeatherAlertCache() {
        rebuildWeatherAlert();
    }

    // CacheRegenerationRequested 처리용 — timeWindow 없이 가장 최근 데이터로 rebuild
    public void rebuildWeatherCache() {
        envLogRepository.findMostRecentWeather().ifPresentOrElse(
            r -> {
                cacheWriter.setEnvironmentWeather(
                    new WeatherCacheValue(r.nx(), r.ny(), r.temperature(), r.weatherCondition(), r.forecastedAt()));
                log.info("environment:weather:seoul SET (regeneration)");
            },
            () -> log.warn("environment:weather:seoul rebuild skipped: no weather record in DB")
        );
    }

    public void rebuildAirQualityCache() {
        envLogRepository.findMostRecentAirQuality().ifPresentOrElse(
            r -> {
                cacheWriter.setEnvironmentAirQuality(
                    new AirQualityCacheValue(r.stationName(), r.aqi(), r.grade(), r.measuredAt()));
                log.info("environment:air-quality:seoul SET (regeneration)");
            },
            () -> log.warn("environment:air-quality:seoul rebuild skipped: no air quality record in DB")
        );
    }

    // weather_alert_log table은 MVP 스키마에 없음 — no_data placeholder를 SET
    private void rebuildWeatherAlert() {
        cacheWriter.setEnvironmentWeatherAlert(WeatherAlertCacheValue.noData());
        log.info("environment:weather-alert:seoul SET: no_data placeholder (weather_alert_log not in MVP schema)");
    }

    // 가장 최근 weather record를 environment:weather:seoul 에 단일 키로 SET
    private void rebuildWeather(String timeWindow) {
        List<WeatherLogRecord> records = envLogRepository.findLatestWeatherByTimeWindow(timeWindow);
        if (records.isEmpty()) {
            log.warn("environment:weather:seoul rebuild skipped: no weather records for timeWindow={}", timeWindow);
            return;
        }
        WeatherLogRecord r = records.get(0);
        WeatherCacheValue value = new WeatherCacheValue(r.nx(), r.ny(), r.temperature(), r.weatherCondition(), r.forecastedAt());
        cacheWriter.setEnvironmentWeather(value);
        log.info("environment:weather:seoul SET: timeWindow={}", timeWindow);
    }

    // 가장 최근 air quality record를 environment:air-quality:seoul 에 단일 키로 SET
    private void rebuildAirQuality(String timeWindow) {
        List<AirQualityLogRecord> records = envLogRepository.findLatestAirQualityByTimeWindow(timeWindow);
        if (records.isEmpty()) {
            log.warn("environment:air-quality:seoul rebuild skipped: no air quality records for timeWindow={}", timeWindow);
            return;
        }
        AirQualityLogRecord r = records.get(0);
        AirQualityCacheValue value = new AirQualityCacheValue(r.stationName(), r.aqi(), r.grade(), r.measuredAt());
        cacheWriter.setEnvironmentAirQuality(value);
        log.info("environment:air-quality:seoul SET: timeWindow={}", timeWindow);
    }
}
