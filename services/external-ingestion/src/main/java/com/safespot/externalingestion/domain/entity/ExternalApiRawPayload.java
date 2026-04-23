package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "external_api_raw_payload",
    indexes = {
        @Index(name = "idx_ext_raw_source_collected", columnList = "source_id, collected_at DESC"),
        @Index(name = "idx_ext_raw_payload_hash", columnList = "payload_hash"),
        @Index(name = "idx_ext_raw_retention", columnList = "retention_expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiRawPayload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "raw_id")
    private Long rawId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private ExternalApiExecutionLog executionLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private ExternalApiSource source;

    @Lob
    @Column(name = "request_url", columnDefinition = "TEXT")
    private String requestUrl;

    @Lob
    @Column(name = "request_params_json", columnDefinition = "TEXT")
    private String requestParamsJson;

    @Lob
    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Lob
    @Column(name = "response_meta_json", columnDefinition = "TEXT")
    private String responseMetaJson;

    @Column(name = "payload_hash", length = 128)
    private String payloadHash;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt = OffsetDateTime.now();

    @Column(name = "retention_expires_at")
    private OffsetDateTime retentionExpiresAt;
}
