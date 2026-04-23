package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.Shelter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    @Query("""
            SELECT s FROM Shelter s
            WHERE s.latitude BETWEEN :latMin AND :latMax
              AND s.longitude BETWEEN :lngMin AND :lngMax
              AND (:disasterType IS NULL OR s.disasterType = :disasterType)
            """)
    List<Shelter> findByBoundingBoxAndDisasterType(
            @Param("latMin") BigDecimal latMin,
            @Param("latMax") BigDecimal latMax,
            @Param("lngMin") BigDecimal lngMin,
            @Param("lngMax") BigDecimal lngMax,
            @Param("disasterType") String disasterType
    );
}
