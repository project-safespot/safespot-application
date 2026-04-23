package com.safespot.apicore.domain.entity;

import com.safespot.apicore.domain.enums.HealthStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "entry_detail")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detail_id")
    private Long detailId;

    @Column(name = "entry_id", nullable = false, unique = true)
    private Long entryId;

    @Column(name = "family_info", columnDefinition = "TEXT")
    private String familyInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 20)
    @Builder.Default
    private HealthStatus healthStatus = HealthStatus.정상;

    @Column(name = "health_note", columnDefinition = "TEXT")
    private String healthNote;

    @Column(name = "special_protection_flag", nullable = false)
    @Builder.Default
    private Boolean specialProtectionFlag = false;

    @Column(name = "support_note", columnDefinition = "TEXT")
    private String supportNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    public void update(String familyInfo, HealthStatus healthStatus, Boolean specialProtectionFlag) {
        if (familyInfo != null) this.familyInfo = familyInfo;
        if (healthStatus != null) this.healthStatus = healthStatus;
        if (specialProtectionFlag != null) this.specialProtectionFlag = specialProtectionFlag;
        this.updatedAt = OffsetDateTime.now();
    }
}
