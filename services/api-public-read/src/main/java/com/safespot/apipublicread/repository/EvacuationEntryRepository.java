package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.EvacuationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvacuationEntryRepository extends JpaRepository<EvacuationEntry, Long> {

    @Query("SELECT COUNT(e) FROM EvacuationEntry e WHERE e.shelterId = :shelterId AND e.entryStatus = 'ENTERED'")
    long countCurrentOccupancy(@Param("shelterId") Long shelterId);
}
