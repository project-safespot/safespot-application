package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.DisasterAlert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisasterAlertRepository extends JpaRepository<DisasterAlert, Long> {
}
