package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "external_api_schedule",
    indexes = {
        @Index(name = "idx_ext_schedule_next_run", columnList = "next_scheduled_at, is_enabled"),
        @Index(name = "idx_ext_schedule_source", columnList = "source_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private ExternalApiSource source;

    @Column(name = "schedule_name", nullable = false, length = 100)
    private String scheduleName;

    @Column(name = "cron_expr", length = 100)
    private String cronExpr;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Seoul";

    @Lob
    @Column(name = "request_params_json", columnDefinition = "TEXT")
    private String requestParamsJson;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "next_scheduled_at")
    private OffsetDateTime nextScheduledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
