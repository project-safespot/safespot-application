package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "disaster_alert_detail",
    indexes = {
        @Index(name = "idx_alert_detail_type", columnList = "detail_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class DisasterAlertDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detail_id")
    private Long detailId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id", nullable = false, unique = true)
    private DisasterAlert alert;

    @Column(name = "detail_type", nullable = false, length = 30)
    private String detailType;

    @Column(name = "magnitude", precision = 4, scale = 1)
    private BigDecimal magnitude;

    @Column(name = "epicenter", length = 255)
    private String epicenter;

    @Column(name = "intensity", length = 20)
    private String intensity;

    @Lob
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
