package com.safespot.scenariosimulator.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evacuation_entry")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimEvacuationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "visitor_name", length = 50)
    private String visitorName;

    @Column(name = "visitor_phone", length = 20)
    private String visitorPhone;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "entry_status", nullable = false, length = 15)
    @Builder.Default
    private String entryStatus = "ENTERED";

    @Column(name = "entered_at", nullable = false)
    @Builder.Default
    private OffsetDateTime enteredAt = OffsetDateTime.now();

    @Column(name = "exited_at")
    private OffsetDateTime exitedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;
}
