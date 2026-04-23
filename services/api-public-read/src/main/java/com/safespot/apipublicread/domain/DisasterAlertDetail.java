package com.safespot.apipublicread.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "disaster_alert_detail")
@Getter
@NoArgsConstructor
public class DisasterAlertDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detail_id")
    private Long detailId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false, unique = true)
    private DisasterAlert disasterAlert;

    @Column(name = "detail_type", nullable = false)
    private String detailType;

    @Column(name = "magnitude", precision = 4, scale = 1)
    private BigDecimal magnitude;

    @Column(name = "epicenter")
    private String epicenter;

    @Column(name = "intensity")
    private String intensity;

    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }
}
