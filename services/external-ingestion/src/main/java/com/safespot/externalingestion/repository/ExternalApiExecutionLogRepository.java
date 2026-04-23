package com.safespot.externalingestion.repository;

import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.domain.enums.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalApiExecutionLogRepository extends JpaRepository<ExternalApiExecutionLog, Long> {
    List<ExternalApiExecutionLog> findTop10BySourceOrderByStartedAtDesc(ExternalApiSource source);
    List<ExternalApiExecutionLog> findByExecutionStatus(ExecutionStatus status);
}
