package com.safespot.scenariosimulator.controller;

import com.safespot.scenariosimulator.common.ApiResponse;
import com.safespot.scenariosimulator.dto.request.CleanupRequest;
import com.safespot.scenariosimulator.dto.request.DisasterAlertRequest;
import com.safespot.scenariosimulator.dto.request.ResidentBulkRequest;
import com.safespot.scenariosimulator.dto.request.ScenarioRunRequest;
import com.safespot.scenariosimulator.dto.response.CleanupResponse;
import com.safespot.scenariosimulator.dto.response.DisasterAlertResponse;
import com.safespot.scenariosimulator.dto.response.ResidentBulkResponse;
import com.safespot.scenariosimulator.dto.response.ScenarioRunResponse;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.service.CleanupService;
import com.safespot.scenariosimulator.service.DisasterAlertSimulatorService;
import com.safespot.scenariosimulator.service.ResidentBulkSimulatorService;
import com.safespot.scenariosimulator.service.ScenarioRunnerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/internal/test")
@RequiredArgsConstructor
public class ScenarioSimulatorController {

    private final DisasterAlertSimulatorService disasterAlertService;
    private final ResidentBulkSimulatorService residentBulkService;
    private final ScenarioRunnerService scenarioRunnerService;
    private final CleanupService cleanupService;
    private final SimulatorMetrics metrics;

    @PostMapping("/disaster-alerts")
    public ResponseEntity<ApiResponse<DisasterAlertResponse>> createDisasterAlerts(
            @Valid @RequestBody DisasterAlertRequest request) {
        metrics.incRequests();
        log.info("[SIM] POST /disaster-alerts: type={} region={} level={} count={}",
                request.getDisasterType(), request.getRegion(), request.getLevel(), request.getCount());

        String scenarioId = UUID.randomUUID().toString();
        DisasterAlertResponse response = disasterAlertService.createAlerts(request, scenarioId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/residents/bulk")
    public ResponseEntity<ApiResponse<ResidentBulkResponse>> createBulkResidents(
            @Valid @RequestBody ResidentBulkRequest request) {
        metrics.incRequests();
        log.info("[SIM] POST /residents/bulk: type={} region={} count={} distribution={}",
                request.getDisasterType(), request.getRegion(), request.getResidentCount(), request.getDistribution());

        String scenarioId = request.getScenarioId() != null ? request.getScenarioId() : UUID.randomUUID().toString();
        ResidentBulkResponse response = residentBulkService.createBulkResidents(request, scenarioId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/scenarios/run")
    public ResponseEntity<ApiResponse<ScenarioRunResponse>> runScenario(
            @Valid @RequestBody ScenarioRunRequest request) {
        metrics.incRequests();
        log.info("[SIM] POST /scenarios/run: name={} type={} region={} level={}",
                request.getScenarioName(),
                request.getDisaster().getDisasterType(),
                request.getDisaster().getRegion(),
                request.getDisaster().getLevel());

        ScenarioRunResponse response = scenarioRunnerService.run(request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/cleanup")
    public ResponseEntity<ApiResponse<CleanupResponse>> cleanup(
            @Valid @RequestBody CleanupRequest request) {
        metrics.incRequests();
        log.info("[SIM] POST /cleanup: scenarioId={}", request.getScenarioId());

        CleanupResponse response = cleanupService.cleanup(request.getScenarioId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
