package com.safespot.apicore.domain.entity;

import com.safespot.apicore.domain.enums.EventHistoryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "evacuation_event_history")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvacuationEventHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "shelter_id", nullable = false)
    private Long shelterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventHistoryType eventType;

    @Column(name = "prev_status", length = 30)
    private String prevStatus;

    @Column(name = "next_status", nullable = false, length = 30)
    private String nextStatus;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    @PrePersist
    void prePersist() {
        if (recordedAt == null) recordedAt = OffsetDateTime.now();
    }
}
