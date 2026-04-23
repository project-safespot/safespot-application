package com.safespot.externalingestion.domain.entity;

import com.safespot.externalingestion.domain.enums.ExecutionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "external_api_execution_log",
    indexes = {
        @Index(name = "idx_ext_exec_source_started", columnList = "source_id, started_at DESC"),
        @Index(name = "idx_ext_exec_status_started", columnList = "execution_status, started_at DESC")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiExecutionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "execution_id")
    private Long executionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    private ExternalApiSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private ExternalApiSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 20)
    private ExecutionStatus executionStatus;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "records_fetched", nullable = false)
    private int recordsFetched = 0;

    @Column(name = "records_normalized", nullable = false)
    private int recordsNormalized = 0;

    @Column(name = "records_failed", nullable = false)
    private int recordsFailed = 0;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
