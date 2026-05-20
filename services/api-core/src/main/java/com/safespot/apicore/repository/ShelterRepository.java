package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.ShelterStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShelterRepository extends JpaRepository<Shelter, Long> {

    interface AdminShelterRow {
        Long getShelterId();
        String getName();
        String getAddress();
        String getShelterType();
        DisasterType getDisasterType();
        Integer getCapacity();
        ShelterStatus getShelterStatus();
        String getManager();
        String getContact();
        Long getCurrentOccupants();
    }

    long countByShelterStatus(ShelterStatus status);

    @Query(
            value = """
                    SELECT
                        s.shelterId AS shelterId,
                        s.name AS name,
                        s.address AS address,
                        s.shelterType AS shelterType,
                        s.disasterType AS disasterType,
                        s.capacity AS capacity,
                        s.shelterStatus AS shelterStatus,
                        s.manager AS manager,
                        s.contact AS contact,
                        COALESCE(COUNT(e.entryId), 0) AS currentOccupants
                    FROM Shelter s
                    LEFT JOIN EvacuationEntry e
                        ON e.shelterId = s.shelterId
                       AND e.entryStatus = com.safespot.apicore.domain.enums.EntryStatus.ENTERED
                    WHERE (:keyword IS NULL
                           OR :keyword = ''
                           OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                           OR LOWER(s.address) LIKE LOWER(CONCAT('%', :keyword, '%')))
                      AND (:status IS NULL OR s.shelterStatus = :status)
                    GROUP BY
                        s.shelterId, s.name, s.address, s.shelterType, s.disasterType,
                        s.capacity, s.shelterStatus, s.manager, s.contact
                    ORDER BY s.shelterId ASC
                    """,
            countQuery = """
                    SELECT COUNT(s)
                    FROM Shelter s
                    WHERE (:keyword IS NULL
                           OR :keyword = ''
                           OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                           OR LOWER(s.address) LIKE LOWER(CONCAT('%', :keyword, '%')))
                      AND (:status IS NULL OR s.shelterStatus = :status)
                    """
    )
    Page<AdminShelterRow> searchAdminShelters(
            @Param("keyword") String keyword,
            @Param("status") ShelterStatus status,
            Pageable pageable
    );

    @Query("SELECT COUNT(s) FROM Shelter s WHERE s.shelterId IN " +
           "(SELECT e.shelterId FROM EvacuationEntry e WHERE e.entryStatus = 'ENTERED' " +
           "GROUP BY e.shelterId HAVING COUNT(e) >= s.capacity)")
    long countFullShelters();
}
