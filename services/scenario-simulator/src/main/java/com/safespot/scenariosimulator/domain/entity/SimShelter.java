package com.safespot.scenariosimulator.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "shelter")
@Getter
@NoArgsConstructor
public class SimShelter {

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

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "manager", length = 50)
    private String manager;

    @Column(name = "contact", length = 50)
    private String contact;

    @Column(name = "shelter_status", nullable = false, length = 20)
    private String shelterStatus;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
