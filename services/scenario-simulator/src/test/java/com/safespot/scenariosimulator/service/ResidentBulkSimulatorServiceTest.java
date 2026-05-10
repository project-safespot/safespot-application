package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.SimEvacuationEntry;
import com.safespot.scenariosimulator.domain.entity.SimShelter;
import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import com.safespot.scenariosimulator.dto.request.ResidentBulkRequest;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimEvacuationEntryRepository;
import com.safespot.scenariosimulator.repository.SimShelterRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResidentBulkSimulatorServiceTest {

    @Mock SimShelterRepository shelterRepository;
    @Mock SimEvacuationEntryRepository evacuationEntryRepository;
    @Mock TestScenarioRecordRepository scenarioRecordRepository;
    @Mock SimulatorEventPublisher eventPublisher;
    @Mock SimulatorMetrics metrics;

    ResidentBulkSimulatorService service;

    @BeforeEach
    void setUp() {
        service = new ResidentBulkSimulatorService(
                shelterRepository, evacuationEntryRepository,
                scenarioRecordRepository, eventPublisher, metrics);
    }

    @Test
    void generates_non_null_scenario_name_when_missing() {
        ResidentBulkRequest request = baseRequest(null);
        mockSingleShelter();
        when(evacuationEntryRepository.save(any())).thenReturn(SimEvacuationEntry.builder()
                .entryId(10L)
                .shelterId(1L)
                .entryStatus("ENTERED")
                .build());

        service.createBulkResidents(request, "scenario-123");

        TestScenarioRecord record = captureScenarioRecord();
        assertThat(record.getScenarioName())
                .isNotBlank()
                .startsWith("BULK_RESIDENTS_EARTHQUAKE_SEOUL_");
    }

    @Test
    void uses_request_scenario_name_when_provided() {
        ResidentBulkRequest request = baseRequest("CUSTOM_BULK_SCENARIO");
        mockSingleShelter();
        when(evacuationEntryRepository.save(any())).thenReturn(SimEvacuationEntry.builder()
                .entryId(11L)
                .shelterId(1L)
                .entryStatus("ENTERED")
                .build());

        service.createBulkResidents(request, "scenario-456");

        TestScenarioRecord record = captureScenarioRecord();
        assertThat(record.getScenarioName()).isEqualTo("CUSTOM_BULK_SCENARIO");
    }

    private ResidentBulkRequest baseRequest(String scenarioName) {
        return ResidentBulkRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .residentCount(1)
                .distribution(ResidentDistribution.RANDOM)
                .entryStatus("ENTERED")
                .publishEvents(false)
                .scenarioName(scenarioName)
                .build();
    }

    private void mockSingleShelter() {
        SimShelter shelter = new SimShelter();
        setField(shelter, "shelterId", 1L);
        setField(shelter, "capacity", 10);
        when(shelterRepository.findByDisasterTypeAndShelterStatus("EARTHQUAKE", "OPERATING"))
                .thenReturn(List.of(shelter));
    }

    private TestScenarioRecord captureScenarioRecord() {
        ArgumentCaptor<TestScenarioRecord> captor = ArgumentCaptor.forClass(TestScenarioRecord.class);
        verify(scenarioRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
