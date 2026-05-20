package com.safespot.apicore.repository;

import com.safespot.apicore.domain.entity.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {

    @Query("""
            SELECT l FROM AdminAuditLog l
            WHERE (:action IS NULL OR :action = '' OR l.action = :action)
              AND (:targetType IS NULL OR :targetType = '' OR l.targetType = :targetType)
              AND (:fromDateTime IS NULL OR l.createdAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR l.createdAt < :toDateTime)
            """)
    Page<AdminAuditLog> searchLogs(
            @Param("action") String action,
            @Param("targetType") String targetType,
            @Param("fromDateTime") OffsetDateTime fromDateTime,
            @Param("toDateTime") OffsetDateTime toDateTime,
            Pageable pageable
    );
}
