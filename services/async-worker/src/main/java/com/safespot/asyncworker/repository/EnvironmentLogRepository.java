package com.safespot.asyncworker.repository;

import java.util.List;
import java.util.Optional;

public interface EnvironmentLogRepository {

    // EnvironmentDataCollected 처리용 — timeWindow 기준 윈도우 내 최신 레코드
    List<WeatherLogRecord> findLatestWeatherByTimeWindow(String timeWindow);

    List<AirQualityLogRecord> findLatestAirQualityByTimeWindow(String timeWindow);

    // CacheRegenerationRequested 처리용 — timeWindow 없이 가장 최근 레코드
    Optional<WeatherLogRecord> findMostRecentWeather();

    Optional<AirQualityLogRecord> findMostRecentAirQuality();
}
