package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.AirQualityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface AirQualityLogRepository extends JpaRepository<AirQualityLog, Long> {
    boolean existsByStationNameAndMeasuredAt(String stationName, OffsetDateTime measuredAt);
}
