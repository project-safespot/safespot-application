package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.WeatherLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WeatherLogRepository extends JpaRepository<WeatherLog, Long> {

    @Query("""
            SELECT w FROM WeatherLog w
            WHERE w.nx = :nx AND w.ny = :ny
            ORDER BY w.forecastDt DESC
            LIMIT 1
            """)
    Optional<WeatherLog> findLatestByNxAndNy(@Param("nx") int nx, @Param("ny") int ny);
}
