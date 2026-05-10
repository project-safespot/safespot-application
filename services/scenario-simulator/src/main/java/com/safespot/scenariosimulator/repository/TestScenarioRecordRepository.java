package com.safespot.scenariosimulator.repository;

import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestScenarioRecordRepository extends JpaRepository<TestScenarioRecord, Long> {

    List<TestScenarioRecord> findByScenarioId(String scenarioId);

    List<TestScenarioRecord> findByScenarioIdAndTargetTable(String scenarioId, String targetTable);

    void deleteByScenarioId(String scenarioId);
}
