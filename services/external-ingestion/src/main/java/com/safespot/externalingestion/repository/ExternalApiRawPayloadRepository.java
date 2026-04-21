package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalApiRawPayloadRepository extends JpaRepository<ExternalApiRawPayload, Long> {
    boolean existsByPayloadHash(String payloadHash);
    Optional<ExternalApiRawPayload> findByPayloadHash(String payloadHash);
}
