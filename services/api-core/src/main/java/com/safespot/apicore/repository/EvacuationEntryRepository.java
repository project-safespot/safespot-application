package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.EvacuationEntry;
import com.safespot.apicore.domain.enums.EntryStatus;
import com.safespot.apicore.domain.enums.HealthStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface EvacuationEntryRepository extends JpaRepository<EvacuationEntry, Long> {

    interface EvacuationEntryListRow {
        Long getEntryId();
        Long getShelterId();
        String getShelterName();
        Long getAlertId();
        Long getUserId();
        String getVisitorName();
        String getVisitorPhone();
        String getAddress();
        EntryStatus getEntryStatus();
        OffsetDateTime getEnteredAt();
        OffsetDateTime getExitedAt();
        String getNote();
        String getFamilyInfo();
        HealthStatus getHealthStatus();
        Boolean getSpecialProtectionFlag();
    }

    List<EvacuationEntry> findByShelterIdAndEntryStatus(Long shelterId, EntryStatus status);

    List<EvacuationEntry> findByShelterId(Long shelterId);

    long countByShelterIdAndEntryStatus(Long shelterId, EntryStatus status);

    @Query("""
            SELECT
                e.entryId AS entryId,
                e.shelterId AS shelterId,
                s.name AS shelterName,
                e.alertId AS alertId,
                e.userId AS userId,
                e.visitorName AS visitorName,
                e.visitorPhone AS visitorPhone,
                e.address AS address,
                e.entryStatus AS entryStatus,
                e.enteredAt AS enteredAt,
                e.exitedAt AS exitedAt,
                e.note AS note,
                d.familyInfo AS familyInfo,
                d.healthStatus AS healthStatus,
                d.specialProtectionFlag AS specialProtectionFlag
            FROM EvacuationEntry e
            JOIN Shelter s ON s.shelterId = e.shelterId
            LEFT JOIN EntryDetail d ON d.entryId = e.entryId
            WHERE (:shelterId IS NULL OR e.shelterId = :shelterId)
              AND (:entryStatus IS NULL OR e.entryStatus = :entryStatus)
              AND (
                  :keyword IS NULL OR :keyword = ''
                  OR LOWER(e.visitorName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR LOWER(e.visitorPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
              AND (
                  :specialOnly IS NULL
                  OR :specialOnly = false
                  OR d.specialProtectionFlag = true
              )
            """)
    Page<EvacuationEntryListRow> searchAdminEntries(
            @Param("shelterId") Long shelterId,
            @Param("entryStatus") EntryStatus entryStatus,
            @Param("keyword") String keyword,
            @Param("specialOnly") Boolean specialOnly,
            Pageable pageable
    );

    @Query("SELECT e.shelterId, COUNT(e) FROM EvacuationEntry e WHERE e.entryStatus = :status GROUP BY e.shelterId")
    List<Object[]> countByEntryStatusGroupByShelterId(@Param("status") EntryStatus status);
}
