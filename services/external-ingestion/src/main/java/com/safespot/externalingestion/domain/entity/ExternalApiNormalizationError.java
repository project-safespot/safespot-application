package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "external_api_normalization_error",
    indexes = {
        @Index(name = "idx_ext_norm_err_source_created", columnList = "source_id, created_at DESC"),
        @Index(name = "idx_ext_norm_err_resolved", columnList = "resolved, created_at DESC")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiNormalizationError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "error_id")
    private Long errorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private ExternalApiExecutionLog executionLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_id")
    private ExternalApiRawPayload rawPayload;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private ExternalApiSource source;

    @Column(name = "target_table", nullable = false, length = 100)
    private String targetTable;

    @Column(name = "failed_field", length = 100)
    private String failedField;

    @Lob
    @Column(name = "raw_fragment", columnDefinition = "TEXT")
    private String rawFragment;

    @Lob
    @Column(name = "error_reason", nullable = false, columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    @Lob
    @Column(name = "resolved_note", columnDefinition = "TEXT")
    private String resolvedNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
