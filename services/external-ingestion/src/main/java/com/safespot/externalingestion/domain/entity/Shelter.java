package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shelter",
    indexes = {
        @Index(name = "idx_shelter_shelter_type", columnList = "shelter_type"),
        @Index(name = "idx_shelter_disaster_type", columnList = "disaster_type"),
        @Index(name = "idx_shelter_status", columnList = "shelter_status"),
        @Index(name = "idx_shelter_location", columnList = "latitude, longitude")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class Shelter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shelter_id")
    private Long shelterId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "shelter_type", nullable = false, length = 50)
    private String shelterType;

    @Column(name = "disaster_type", nullable = false, length = 20)
    private String disasterType;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "latitude", nullable = true, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = true, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "capacity", nullable = true)
    private Integer capacity;

    @Column(name = "manager", length = 50)
    private String manager;

    @Column(name = "contact", length = 50)
    private String contact;

    @Column(name = "shelter_status", nullable = false, length = 20)
    private String shelterStatus = "OPERATING";

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /** 외부 수집이 갱신할 수 있는 컬럼 업데이트 (shelter_status 제외) */
    public void updateFromExternalSource(String name, String shelterType, String disasterType,
                                         String address, BigDecimal latitude, BigDecimal longitude,
                                         Integer capacity, String manager, String contact, String note) {
        this.name = name;
        this.shelterType = shelterType;
        this.disasterType = disasterType;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capacity = capacity;
        this.manager = manager;
        this.contact = contact;
        this.note = note;
    }
}
