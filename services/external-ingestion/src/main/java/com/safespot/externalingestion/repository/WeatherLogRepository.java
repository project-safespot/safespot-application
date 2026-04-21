package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.WeatherLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface WeatherLogRepository extends JpaRepository<WeatherLog, Long> {
    boolean existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(
        Integer nx, Integer ny, LocalDate baseDate, String baseTime, OffsetDateTime forecastDt);
}
