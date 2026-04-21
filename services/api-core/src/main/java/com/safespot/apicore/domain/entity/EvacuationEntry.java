package com.safespot.apicore.domain.entity;

import com.safespot.apicore.domain.enums.EntryStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evacuation_entry")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationEntry {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_status", nullable = false, length = 15)
    @Builder.Default
    private EntryStatus entryStatus = EntryStatus.ENTERED;

    @Column(name = "entered_at", nullable = false)
    private OffsetDateTime enteredAt;

    @Column(name = "exited_at")
    private OffsetDateTime exitedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @PrePersist
    void prePersist() {
        if (enteredAt == null) enteredAt = OffsetDateTime.now();
    }

    public void exit() {
        this.entryStatus = EntryStatus.EXITED;
        this.exitedAt = OffsetDateTime.now();
    }

    public void updateInfo(String address, String note) {
        if (address != null) this.address = address;
        if (note != null) this.note = note;
    }
}
