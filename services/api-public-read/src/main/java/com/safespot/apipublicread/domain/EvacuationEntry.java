package com.safespot.apipublicread.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evacuation_entry")
@Getter
@NoArgsConstructor
public class EvacuationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Column(name = "entry_status", nullable = false)
    private String entryStatus;

    @Column(name = "entered_at", nullable = false)
    private OffsetDateTime enteredAt;

    @Column(name = "exited_at")
    private OffsetDateTime exitedAt;
}
