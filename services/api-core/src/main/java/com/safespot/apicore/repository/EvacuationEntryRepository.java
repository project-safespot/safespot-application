package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.EvacuationEntry;
import com.safespot.apicore.domain.enums.EntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvacuationEntryRepository extends JpaRepository<EvacuationEntry, Long> {

    List<EvacuationEntry> findByShelterIdAndEntryStatus(Long shelterId, EntryStatus status);

    List<EvacuationEntry> findByShelterId(Long shelterId);

    long countByShelterIdAndEntryStatus(Long shelterId, EntryStatus status);
}
