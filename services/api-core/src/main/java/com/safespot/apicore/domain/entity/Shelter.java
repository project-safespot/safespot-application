package com.safespot.apicore.domain.entity;

import com.safespot.apicore.domain.enums.DisasterType;
import com.safespot.apicore.domain.enums.ShelterStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shelter")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shelter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shelter_id")
    private Long shelterId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "shelter_type", nullable = false, length = 50)
    private String shelterType;

    @Enumerated(EnumType.STRING)
    @Column(name = "disaster_type", nullable = false, length = 20)
    private DisasterType disasterType;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "manager", length = 50)
    private String manager;

    @Column(name = "contact", length = 50)
    private String contact;

    @Enumerated(EnumType.STRING)
    @Column(name = "shelter_status", nullable = false, length = 20)
    @Builder.Default
    private ShelterStatus shelterStatus = ShelterStatus.운영중;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    public void update(Integer capacity, ShelterStatus status, String manager, String contact, String note) {
        if (capacity != null) this.capacity = capacity;
        if (status != null) this.shelterStatus = status;
        if (manager != null) this.manager = manager;
        if (contact != null) this.contact = contact;
        if (note != null) this.note = note;
        this.updatedAt = OffsetDateTime.now();
    }
}
