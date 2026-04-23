package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.DisasterAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DisasterAlertRepository extends JpaRepository<DisasterAlert, Long> {

    @Query("""
            SELECT a FROM DisasterAlert a
            WHERE (:region IS NULL OR a.region = :region)
              AND (:disasterType IS NULL OR a.disasterType = :disasterType)
            ORDER BY a.issuedAt DESC
            """)
    List<DisasterAlert> findAlerts(
            @Param("region") String region,
            @Param("disasterType") String disasterType
    );

    @Query("""
            SELECT a FROM DisasterAlert a
            WHERE a.disasterType = :disasterType
              AND a.region = :region
            ORDER BY a.issuedAt DESC
            LIMIT 1
            """)
    Optional<DisasterAlert> findLatest(
            @Param("disasterType") String disasterType,
            @Param("region") String region
    );
}
