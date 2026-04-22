package com.safespot.asyncworker.service.environment;

import com.safespot.asyncworker.payload.EnvironmentDataCollectedPayload;
import com.safespot.asyncworker.redis.AirQualityCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.WeatherCacheValue;
import com.safespot.asyncworker.repository.AirQualityLogRecord;
import com.safespot.asyncworker.repository.EnvironmentLogRepository;
import com.safespot.asyncworker.repository.WeatherLogRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentCacheService {

    private static final String WEATHER = "WEATHER";
    private static final String AIR_QUALITY = "AIR_QUALITY";

    private final EnvironmentLogRepository envLogRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuild(EnvironmentDataCollectedPayload payload) {
        switch (payload.collectionType()) {
            case WEATHER     -> rebuildWeather(payload.timeWindow());
            case AIR_QUALITY -> rebuildAirQuality(payload.timeWindow());
            default -> log.warn("Unknown collectionType: {}", payload.collectionType());
        }
    }

    private void rebuildWeather(String timeWindow) {
        List<WeatherLogRecord> records = envLogRepository.findLatestWeatherByTimeWindow(timeWindow);
        for (WeatherLogRecord r : records) {
            WeatherCacheValue value = new WeatherCacheValue(
                r.nx(), r.ny(), r.temperature(), r.weatherCondition(), r.forecastedAt()
            );
            cacheWriter.setWeather(value);
        }
        log.info("Weather cache rebuilt: timeWindow={}, keyCount={}", timeWindow, records.size());
    }

    private void rebuildAirQuality(String timeWindow) {
        List<AirQualityLogRecord> records = envLogRepository.findLatestAirQualityByTimeWindow(timeWindow);
        for (AirQualityLogRecord r : records) {
            AirQualityCacheValue value = new AirQualityCacheValue(
                r.stationName(), r.aqi(), r.grade(), r.measuredAt()
            );
            cacheWriter.setAirQuality(value);
        }
        log.info("Air quality cache rebuilt: timeWindow={}, keyCount={}", timeWindow, records.size());
    }
}
