package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "external_api_source")
@Getter
@Setter
@NoArgsConstructor
public class ExternalApiSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "source_code", nullable = false, unique = true, length = 50)
    private String sourceCode;

    @Column(name = "source_name", nullable = false, length = 100)
    private String sourceName;

    @Column(name = "provider", nullable = false, length = 100)
    private String provider;

    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "auth_type", nullable = false, length = 30)
    private String authType;

    @Lob
    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
