package com.safespot.asyncworker.repository;

import java.util.List;

public interface EnvironmentLogRepository {

    List<WeatherLogRecord> findLatestWeatherByTimeWindow(String timeWindow);

    List<AirQualityLogRecord> findLatestAirQualityByTimeWindow(String timeWindow);
}
