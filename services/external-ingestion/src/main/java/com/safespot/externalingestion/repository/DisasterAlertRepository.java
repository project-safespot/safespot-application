package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.DisasterAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DisasterAlertRepository extends JpaRepository<DisasterAlert, Long> {
    boolean existsBySourceAndIssuedAt(String source, OffsetDateTime issuedAt);
    Optional<DisasterAlert> findTopByDisasterTypeOrderByIssuedAtDesc(String disasterType);
    List<DisasterAlert> findByExpiredAtIsNullAndLevelIn(List<String> levels);
}
