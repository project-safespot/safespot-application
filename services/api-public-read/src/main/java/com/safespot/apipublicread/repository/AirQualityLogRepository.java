package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.AirQualityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AirQualityLogRepository extends JpaRepository<AirQualityLog, Long> {

    @Query("""
            SELECT a FROM AirQualityLog a
            WHERE a.stationName = :stationName
            ORDER BY a.measuredAt DESC
            LIMIT 1
            """)
    Optional<AirQualityLog> findLatestByStationName(@Param("stationName") String stationName);

    @Query("""
            SELECT a FROM AirQualityLog a
            ORDER BY a.measuredAt DESC
            LIMIT 1
            """)
    Optional<AirQualityLog> findLatest();
}
