package com.safespot.apipublicread.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "disaster_alert")
@Getter
@NoArgsConstructor
public class DisasterAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "disaster_type", nullable = false)
    private String disasterType;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "level", nullable = false)
    private String level;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expired_at")
    private OffsetDateTime expiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToOne(mappedBy = "disasterAlert", fetch = FetchType.LAZY)
    private DisasterAlertDetail detail;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
