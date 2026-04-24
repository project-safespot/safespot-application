package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "disaster_alert",
    indexes = {
        @Index(name = "idx_alert_region_type", columnList = "region, disaster_type"),
        @Index(name = "idx_alert_issued_at", columnList = "issued_at DESC"),
        @Index(name = "idx_alert_source_issued", columnList = "source, issued_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_alert_source_issued_at", columnNames = {"source", "issued_at"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class DisasterAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "raw_type", length = 100)
    private String rawType;

    @Column(name = "disaster_type", nullable = false, length = 20)
    private String disasterType;

    @Column(name = "raw_category_tokens", columnDefinition = "text")
    private String rawCategoryTokens;

    @Column(name = "message_category", length = 20)
    private String messageCategory;

    @Column(name = "raw_level", length = 100)
    private String rawLevel;

    @Column(name = "raw_level_tokens", columnDefinition = "text")
    private String rawLevelTokens;

    @Column(name = "level", length = 10)
    private String level;

    @Column(name = "level_rank")
    private Integer levelRank;

    @Column(name = "region", nullable = false, length = 100)
    private String region;

    @Column(name = "source_region", length = 100)
    private String sourceRegion;

    @Lob
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "is_in_scope")
    private Boolean isInScope;

    @Lob
    @Column(name = "normalization_reason", columnDefinition = "TEXT")
    private String normalizationReason;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
