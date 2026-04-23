package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.ExternalApiNormalizationError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalApiNormalizationErrorRepository extends JpaRepository<ExternalApiNormalizationError, Long> {
    List<ExternalApiNormalizationError> findByResolvedFalseOrderByCreatedAtDesc();
}
