package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.EvacuationEventHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvacuationEventHistoryRepository extends JpaRepository<EvacuationEventHistory, Long> {
}
