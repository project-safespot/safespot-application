package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.DisasterAlertDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DisasterAlertDetailRepository extends JpaRepository<DisasterAlertDetail, Long> {
    Optional<DisasterAlertDetail> findByAlert_AlertId(Long alertId);
}
