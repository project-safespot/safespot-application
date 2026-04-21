package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalApiSourceRepository extends JpaRepository<ExternalApiSource, Long> {
    Optional<ExternalApiSource> findBySourceCode(String sourceCode);
}
