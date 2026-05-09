package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.dto.response.CleanupResponse;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimDisasterAlertRepository;
import com.safespot.scenariosimulator.repository.SimEvacuationEntryRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    private final TestScenarioRecordRepository scenarioRecordRepository;
    private final SimDisasterAlertRepository disasterAlertRepository;
    private final SimEvacuationEntryRepository evacuationEntryRepository;
    private final SimulatorMetrics metrics;

    @Transactional
    public CleanupResponse cleanup(String scenarioId) {
        log.info("[SIM] cleanup started: scenarioId={}", scenarioId);

        List<TestScenarioRecord> records = scenarioRecordRepository.findByScenarioId(scenarioId);
        List<String> skippedReasons = new ArrayList<>();

        int alertsDeleted = 0;
        int entriesDeleted = 0;

        for (TestScenarioRecord record : records) {
            switch (record.getTargetTable()) {
                case "disaster_alert" -> {
                    try {
                        disasterAlertRepository.deleteById(record.getTargetId());
                        alertsDeleted++;
                    } catch (Exception e) {
                        skippedReasons.add("disaster_alert:" + record.getTargetId() + " — " + e.getMessage());
                        log.warn("[SIM] cleanup skipped disaster_alert id={}: {}", record.getTargetId(), e.getMessage());
                    }
                }
                case "evacuation_entry" -> {
                    try {
                        evacuationEntryRepository.deleteById(record.getTargetId());
                        entriesDeleted++;
                    } catch (Exception e) {
                        skippedReasons.add("evacuation_entry:" + record.getTargetId() + " — " + e.getMessage());
                        log.warn("[SIM] cleanup skipped evacuation_entry id={}: {}", record.getTargetId(), e.getMessage());
                    }
                }
                default -> skippedReasons.add(record.getTargetTable() + ":" + record.getTargetId()
                        + " — no cleanup handler for this table");
            }
        }

        int trackingDeleted = records.size();
        scenarioRecordRepository.deleteByScenarioId(scenarioId);

        metrics.incCleanup();

        log.info("[SIM] cleanup done: scenarioId={} alerts={} entries={} tracking={} skipped={}",
                scenarioId, alertsDeleted, entriesDeleted, trackingDeleted, skippedReasons.size());

        return CleanupResponse.builder()
                .scenarioId(scenarioId)
                .disasterAlertsDeleted(alertsDeleted)
                .evacuationEntriesDeleted(entriesDeleted)
                .trackingRecordsDeleted(trackingDeleted)
                .skippedReasons(skippedReasons)
                .build();
    }
}
