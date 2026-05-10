package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.SimDisasterAlert;
import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.domain.enums.DisasterLevel;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.dto.request.DisasterAlertRequest;
import com.safespot.scenariosimulator.dto.response.DisasterAlertResponse;
import com.safespot.scenariosimulator.event.EventEnvelope;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimDisasterAlertRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisasterAlertSimulatorServiceTest {

    @Mock SimDisasterAlertRepository disasterAlertRepository;
    @Mock TestScenarioRecordRepository scenarioRecordRepository;
    @Mock SimulatorEventPublisher eventPublisher;
    @Mock SimulatorMetrics metrics;

    DisasterAlertSimulatorService service;

    @BeforeEach
    void setUp() {
        service = new DisasterAlertSimulatorService(
                disasterAlertRepository, scenarioRecordRepository, eventPublisher, metrics);
    }

    @Test
    void creates_specified_number_of_alerts() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.HIGH)
                .count(3)
                .publishEvents(false)
                .triggerProactiveScale(false)
                .build();

        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(1L)
                .disasterType("EARTHQUAKE")
                .region("seoul")
                .source("SIMULATOR")
                .message("test")
                .build();
        when(disasterAlertRepository.save(any())).thenReturn(saved);

        DisasterAlertResponse response = service.createAlerts(request, UUID.randomUUID().toString());

        assertThat(response.getCreatedCount()).isEqualTo(3);
        verify(disasterAlertRepository, times(3)).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishes_events_when_publishEvents_true() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.FLOOD)
                .region("SEOUL")
                .level(DisasterLevel.MEDIUM)
                .count(1)
                .publishEvents(true)
                .triggerProactiveScale(false)
                .build();

        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(42L)
                .disasterType("FLOOD")
                .region("seoul")
                .level("MEDIUM")
                .levelRank(2)
                .source("SIMULATOR")
                .message("test flood alert")
                .build();
        when(disasterAlertRepository.save(any())).thenReturn(saved);

        service.createAlerts(request, UUID.randomUUID().toString());

        // Should publish DisasterAlertCreated + CacheRegenerationRequested per alert
        verify(eventPublisher, times(2)).publish(any(EventEnvelope.class));
    }

    @Test
    void publishes_proactive_scale_when_requested() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.CRITICAL)
                .count(1)
                .publishEvents(true)
                .triggerProactiveScale(true)
                .build();

        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(99L)
                .disasterType("EARTHQUAKE")
                .region("seoul")
                .level("CRITICAL")
                .levelRank(4)
                .source("SIMULATOR")
                .message("critical earthquake")
                .build();
        when(disasterAlertRepository.save(any())).thenReturn(saved);

        service.createAlerts(request, UUID.randomUUID().toString());

        // DisasterAlertCreated + CacheRegenerationRequested + ProactiveScaleRequested
        verify(eventPublisher, times(3)).publish(any(EventEnvelope.class));
    }

    @Test
    void alert_has_simulator_source() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.LANDSLIDE)
                .region("SEOUL")
                .level(DisasterLevel.LOW)
                .count(1)
                .publishEvents(false)
                .build();

        ArgumentCaptor<SimDisasterAlert> captor = ArgumentCaptor.forClass(SimDisasterAlert.class);
        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(1L).disasterType("LANDSLIDE").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(captor.capture())).thenReturn(saved);

        service.createAlerts(request, "test-scenario");

        SimDisasterAlert captured = captor.getValue();
        assertThat(captured.getSource()).isEqualTo("SIMULATOR");
        assertThat(captured.getDisasterType()).isEqualTo("LANDSLIDE");
        assertThat(captured.getRegion()).isEqualTo("seoul");
        assertThat(captured.getIsInScope()).isTrue();
    }

    @Test
    void high_level_maps_to_warning_canonical_level() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.HIGH)
                .count(1)
                .publishEvents(false)
                .build();

        ArgumentCaptor<SimDisasterAlert> captor = ArgumentCaptor.forClass(SimDisasterAlert.class);
        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(1L).disasterType("EARTHQUAKE").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(captor.capture())).thenReturn(saved);

        service.createAlerts(request, "test-scenario");

        SimDisasterAlert captured = captor.getValue();
        assertThat(captured.getLevel()).isEqualTo("WARNING");
        assertThat(captured.getRawLevel()).isEqualTo("HIGH");
        assertThat(captured.getLevelRank()).isEqualTo(3);
    }

    @Test
    void low_level_maps_to_interest_canonical_level() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.FLOOD)
                .region("SEOUL")
                .level(DisasterLevel.LOW)
                .count(1)
                .publishEvents(false)
                .build();

        ArgumentCaptor<SimDisasterAlert> captor = ArgumentCaptor.forClass(SimDisasterAlert.class);
        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(2L).disasterType("FLOOD").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(captor.capture())).thenReturn(saved);

        service.createAlerts(request, "test-scenario");

        SimDisasterAlert captured = captor.getValue();
        assertThat(captured.getLevel()).isEqualTo("INTEREST");
        assertThat(captured.getRawLevel()).isEqualTo("LOW");
        assertThat(captured.getLevelRank()).isEqualTo(1);
    }

    @Test
    void medium_level_maps_to_caution_canonical_level() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.LANDSLIDE)
                .region("SEOUL")
                .level(DisasterLevel.MEDIUM)
                .count(1)
                .publishEvents(false)
                .build();

        ArgumentCaptor<SimDisasterAlert> captor = ArgumentCaptor.forClass(SimDisasterAlert.class);
        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(3L).disasterType("LANDSLIDE").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(captor.capture())).thenReturn(saved);

        service.createAlerts(request, "test-scenario");

        SimDisasterAlert captured = captor.getValue();
        assertThat(captured.getLevel()).isEqualTo("CAUTION");
        assertThat(captured.getRawLevel()).isEqualTo("MEDIUM");
        assertThat(captured.getLevelRank()).isEqualTo(2);
    }

    @Test
    void critical_level_maps_to_critical_canonical_level() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.CRITICAL)
                .count(1)
                .publishEvents(false)
                .build();

        ArgumentCaptor<SimDisasterAlert> captor = ArgumentCaptor.forClass(SimDisasterAlert.class);
        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(4L).disasterType("EARTHQUAKE").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(captor.capture())).thenReturn(saved);

        service.createAlerts(request, "test-scenario");

        SimDisasterAlert captured = captor.getValue();
        assertThat(captured.getLevel()).isEqualTo("CRITICAL");
        assertThat(captured.getRawLevel()).isEqualTo("CRITICAL");
        assertThat(captured.getLevelRank()).isEqualTo(4);
    }

    @Test
    void tracks_each_alert_in_scenario_record() {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.HIGH)
                .count(2)
                .publishEvents(false)
                .build();

        SimDisasterAlert saved = SimDisasterAlert.builder()
                .alertId(1L).disasterType("EARTHQUAKE").region("seoul")
                .source("SIMULATOR").message("test").build();
        when(disasterAlertRepository.save(any())).thenReturn(saved);

        service.createAlerts(request, "scenario-123");

        verify(scenarioRecordRepository, times(2)).save(any(TestScenarioRecord.class));
    }
}
