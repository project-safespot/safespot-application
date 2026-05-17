package com.safespot.apipublicread.repository;

import com.safespot.apipublicread.domain.EvacuationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EvacuationEntryRepository extends JpaRepository<EvacuationEntry, Long> {

    interface ShelterOccupancyRow {
        Long getShelterId();
        long getCurrentOccupancy();
    }

    @Query("SELECT COUNT(e) FROM EvacuationEntry e WHERE e.shelterId = :shelterId AND e.entryStatus = 'ENTERED'")
    long countCurrentOccupancy(@Param("shelterId") Long shelterId);

    @Query("""
            SELECT e.shelterId AS shelterId, COUNT(e) AS currentOccupancy
            FROM EvacuationEntry e
            WHERE e.shelterId IN :shelterIds
              AND e.entryStatus = 'ENTERED'
            GROUP BY e.shelterId
            """)
    List<ShelterOccupancyRow> countCurrentOccupancyByShelterIds(@Param("shelterIds") Collection<Long> shelterIds);
}
