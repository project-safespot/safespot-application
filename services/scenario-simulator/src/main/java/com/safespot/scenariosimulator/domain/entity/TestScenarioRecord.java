package com.safespot.scenariosimulator.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "test_scenario_record",
    indexes = {
        @Index(name = "idx_scenario_record_scenario_id", columnList = "scenario_id"),
        @Index(name = "idx_scenario_record_target", columnList = "target_table, target_id")
    }
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestScenarioRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "scenario_id", nullable = false, length = 36)
    private String scenarioId;

    @Column(name = "scenario_name", length = 100)
    private String scenarioName;

    @Column(name = "target_table", nullable = false, length = 100)
    private String targetTable;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
