package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.ShelterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    long countByShelterStatus(ShelterStatus status);

    @Query("SELECT COUNT(s) FROM Shelter s WHERE s.shelterId IN " +
           "(SELECT e.shelterId FROM EvacuationEntry e WHERE e.entryStatus = 'ENTERED' " +
           "GROUP BY e.shelterId HAVING COUNT(e) >= s.capacity)")
    long countFullShelters();
}
