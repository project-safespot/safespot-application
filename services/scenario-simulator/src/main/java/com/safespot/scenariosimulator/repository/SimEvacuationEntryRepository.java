package com.safespot.scenariosimulator.repository;

import com.safespot.scenariosimulator.domain.entity.SimEvacuationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimEvacuationEntryRepository extends JpaRepository<SimEvacuationEntry, Long> {

    List<SimEvacuationEntry> findByAlertId(Long alertId);

    List<SimEvacuationEntry> findByShelterId(Long shelterId);
}
