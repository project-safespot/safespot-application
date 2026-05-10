package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.dto.response.CleanupResponse;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimDisasterAlertRepository;
import com.safespot.scenariosimulator.repository.SimEvacuationEntryRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CleanupServiceTest {

    @Mock TestScenarioRecordRepository scenarioRecordRepository;
    @Mock SimDisasterAlertRepository disasterAlertRepository;
    @Mock SimEvacuationEntryRepository evacuationEntryRepository;
    @Mock SimulatorMetrics metrics;

    CleanupService service;

    @BeforeEach
    void setUp() {
        service = new CleanupService(
                scenarioRecordRepository, disasterAlertRepository,
                evacuationEntryRepository, metrics);
    }

    @Test
    void deletes_tracked_disaster_alerts() {
        String scenarioId = "test-scenario-1";
        List<TestScenarioRecord> records = List.of(
                record(scenarioId, "disaster_alert", 10L),
                record(scenarioId, "disaster_alert", 11L)
        );
        when(scenarioRecordRepository.findByScenarioId(scenarioId)).thenReturn(records);

        CleanupResponse response = service.cleanup(scenarioId);

        assertThat(response.getDisasterAlertsDeleted()).isEqualTo(2);
        assertThat(response.getEvacuationEntriesDeleted()).isEqualTo(0);
        assertThat(response.getTrackingRecordsDeleted()).isEqualTo(2);
        verify(disasterAlertRepository).deleteById(10L);
        verify(disasterAlertRepository).deleteById(11L);
        verify(scenarioRecordRepository).deleteByScenarioId(scenarioId);
    }

    @Test
    void deletes_tracked_evacuation_entries() {
        String scenarioId = "test-scenario-2";
        List<TestScenarioRecord> records = List.of(
                record(scenarioId, "evacuation_entry", 20L),
                record(scenarioId, "evacuation_entry", 21L),
                record(scenarioId, "evacuation_entry", 22L)
        );
        when(scenarioRecordRepository.findByScenarioId(scenarioId)).thenReturn(records);

        CleanupResponse response = service.cleanup(scenarioId);

        assertThat(response.getEvacuationEntriesDeleted()).isEqualTo(3);
        assertThat(response.getDisasterAlertsDeleted()).isEqualTo(0);
        verify(evacuationEntryRepository).deleteById(20L);
        verify(evacuationEntryRepository).deleteById(21L);
        verify(evacuationEntryRepository).deleteById(22L);
    }

    @Test
    void reports_skipped_on_delete_failure() {
        String scenarioId = "test-scenario-3";
        List<TestScenarioRecord> records = List.of(
                record(scenarioId, "disaster_alert", 30L)
        );
        when(scenarioRecordRepository.findByScenarioId(scenarioId)).thenReturn(records);
        doThrow(new RuntimeException("FK violation")).when(disasterAlertRepository).deleteById(30L);

        CleanupResponse response = service.cleanup(scenarioId);

        assertThat(response.getDisasterAlertsDeleted()).isEqualTo(0);
        assertThat(response.getSkippedReasons()).hasSize(1);
        assertThat(response.getSkippedReasons().get(0)).contains("disaster_alert:30");
    }

    @Test
    void handles_unknown_table_by_reporting_skipped() {
        String scenarioId = "test-scenario-4";
        List<TestScenarioRecord> records = List.of(
                record(scenarioId, "some_other_table", 99L)
        );
        when(scenarioRecordRepository.findByScenarioId(scenarioId)).thenReturn(records);

        CleanupResponse response = service.cleanup(scenarioId);

        assertThat(response.getSkippedReasons()).hasSize(1);
        assertThat(response.getSkippedReasons().get(0)).contains("some_other_table");
    }

    @Test
    void returns_zero_counts_when_no_records_exist() {
        String scenarioId = "nonexistent-scenario";
        when(scenarioRecordRepository.findByScenarioId(scenarioId)).thenReturn(List.of());

        CleanupResponse response = service.cleanup(scenarioId);

        assertThat(response.getDisasterAlertsDeleted()).isEqualTo(0);
        assertThat(response.getEvacuationEntriesDeleted()).isEqualTo(0);
        assertThat(response.getTrackingRecordsDeleted()).isEqualTo(0);
        assertThat(response.getSkippedReasons()).isEmpty();
    }

    private TestScenarioRecord record(String scenarioId, String table, Long targetId) {
        return TestScenarioRecord.builder()
                .scenarioId(scenarioId)
                .targetTable(table)
                .targetId(targetId)
                .build();
    }
}
