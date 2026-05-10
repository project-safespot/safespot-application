package com.safespot.scenariosimulator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.scenariosimulator.dto.request.CleanupRequest;
import com.safespot.scenariosimulator.dto.request.DisasterAlertRequest;
import com.safespot.scenariosimulator.dto.request.ResidentBulkRequest;
import com.safespot.scenariosimulator.dto.request.ScenarioRunRequest;
import com.safespot.scenariosimulator.dto.response.CleanupResponse;
import com.safespot.scenariosimulator.dto.response.DisasterAlertResponse;
import com.safespot.scenariosimulator.dto.response.ResidentBulkResponse;
import com.safespot.scenariosimulator.dto.response.ScenarioRunResponse;
import com.safespot.scenariosimulator.domain.enums.DisasterLevel;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.service.CleanupService;
import com.safespot.scenariosimulator.service.DisasterAlertSimulatorService;
import com.safespot.scenariosimulator.service.ResidentBulkSimulatorService;
import com.safespot.scenariosimulator.service.ScenarioRunnerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScenarioSimulatorController.class)
class ScenarioSimulatorControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean DisasterAlertSimulatorService disasterAlertService;
    @MockBean ResidentBulkSimulatorService residentBulkService;
    @MockBean ScenarioRunnerService scenarioRunnerService;
    @MockBean CleanupService cleanupService;
    @MockBean SimulatorMetrics metrics;

    @Test
    void post_disaster_alerts_returns_200() throws Exception {
        DisasterAlertRequest request = DisasterAlertRequest.builder()
                .disasterType(DisasterType.EARTHQUAKE)
                .region("SEOUL")
                .level(DisasterLevel.HIGH)
                .count(2)
                .publishEvents(true)
                .build();

        DisasterAlertResponse response = DisasterAlertResponse.builder()
                .scenarioId("test-scenario")
                .requestedCount(2)
                .createdCount(2)
                .alertIds(List.of(1L, 2L))
                .eventsPublished(4)
                .build();
        when(disasterAlertService.createAlerts(any(), anyString())).thenReturn(response);

        mockMvc.perform(post("/internal/test/disaster-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(2))
                .andExpect(jsonPath("$.data.eventsPublished").value(4));
    }

    @Test
    void post_disaster_alerts_validates_required_fields() throws Exception {
        String invalidBody = "{}";

        mockMvc.perform(post("/internal/test/disaster-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_residents_bulk_returns_200() throws Exception {
        ResidentBulkRequest request = ResidentBulkRequest.builder()
                .disasterType(DisasterType.FLOOD)
                .region("SEOUL")
                .residentCount(100)
                .distribution(ResidentDistribution.RANDOM)
                .publishEvents(true)
                .build();

        ResidentBulkResponse response = ResidentBulkResponse.builder()
                .scenarioId("test-scenario")
                .requestedCount(100)
                .createdCount(100)
                .shelterCount(5)
                .entriesPerShelter(Collections.emptyMap())
                .eventsPublished(105)
                .build();
        when(residentBulkService.createBulkResidents(any(), anyString())).thenReturn(response);

        mockMvc.perform(post("/internal/test/residents/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.createdCount").value(100));
    }

    @Test
    void post_scenarios_run_returns_200() throws Exception {
        String body = """
                {
                  "scenarioName": "SEOUL_HIGH_EARTHQUAKE_LOAD",
                  "disaster": {
                    "disasterType": "EARTHQUAKE",
                    "region": "SEOUL",
                    "level": "HIGH"
                  }
                }
                """;

        ScenarioRunResponse response = ScenarioRunResponse.builder()
                .scenarioId("abc-123")
                .scenarioName("SEOUL_HIGH_EARTHQUAKE_LOAD")
                .startedAt(OffsetDateTime.now())
                .completedAt(OffsetDateTime.now())
                .disasterAlertsCreated(1)
                .residentsCreated(0)
                .eventsPublished(2)
                .status("COMPLETED")
                .build();
        when(scenarioRunnerService.run(any())).thenReturn(response);

        mockMvc.perform(post("/internal/test/scenarios/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void post_cleanup_returns_200() throws Exception {
        CleanupRequest request = new CleanupRequest();
        setField(request, "scenarioId", "abc-123");

        CleanupResponse response = CleanupResponse.builder()
                .scenarioId("abc-123")
                .disasterAlertsDeleted(2)
                .evacuationEntriesDeleted(50)
                .trackingRecordsDeleted(52)
                .skippedReasons(List.of())
                .build();
        when(cleanupService.cleanup(anyString())).thenReturn(response);

        mockMvc.perform(post("/internal/test/cleanup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"abc-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.disasterAlertsDeleted").value(2));
    }

    @Test
    void post_cleanup_validates_required_scenario_id() throws Exception {
        mockMvc.perform(post("/internal/test/cleanup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"\"}"))
                .andExpect(status().isBadRequest());
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
