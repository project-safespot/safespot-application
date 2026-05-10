package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.dto.request.DisasterAlertRequest;
import com.safespot.scenariosimulator.dto.request.ResidentBulkRequest;
import com.safespot.scenariosimulator.dto.request.ScenarioRunRequest;
import com.safespot.scenariosimulator.dto.response.DisasterAlertResponse;
import com.safespot.scenariosimulator.dto.response.ResidentBulkResponse;
import com.safespot.scenariosimulator.dto.response.ScenarioRunResponse;
import com.safespot.scenariosimulator.event.EventEnvelope;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.event.payload.ProactiveScaleRequestedPayload;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenarioRunnerService {

    private final DisasterAlertSimulatorService disasterAlertService;
    private final ResidentBulkSimulatorService residentBulkService;
    private final SimulatorEventPublisher eventPublisher;
    private final SimulatorMetrics metrics;

    public ScenarioRunResponse run(ScenarioRunRequest request) {
        String scenarioId = UUID.randomUUID().toString();
        OffsetDateTime startedAt = OffsetDateTime.now();

        log.info("[SIM] scenario started: scenarioId={} name={} type={} region={} level={}",
                scenarioId, request.getScenarioName(),
                request.getDisaster().getDisasterType(),
                request.getDisaster().getRegion(),
                request.getDisaster().getLevel());

        metrics.incRequests();

        int totalAlerts = 0;
        int totalResidents = 0;
        int totalEvents = 0;
        boolean cacheTriggered = false;
        boolean scaleTriggered = false;

        try {
            // triggerProactiveScale=false here; ScenarioRunnerService publishes it separately below
            DisasterAlertRequest alertReq = buildAlertRequest(request, false);
            DisasterAlertResponse alertResp = disasterAlertService.createAlerts(alertReq, scenarioId);
            totalAlerts = alertResp.getCreatedCount();
            totalEvents += alertResp.getEventsPublished();
            cacheTriggered = alertResp.getEventsPublished() > 0;
            scaleTriggered = request.getScale() != null && request.getScale().isTriggerProactiveScale();

            if (request.getResidents() != null) {
                ResidentBulkRequest residentReq = buildResidentRequest(request, scenarioId);
                ResidentBulkResponse residentResp = residentBulkService.createBulkResidents(residentReq, scenarioId);
                totalResidents = residentResp.getCreatedCount();
                totalEvents += residentResp.getEventsPublished();
            }

            if (request.getScale() != null && request.getScale().isTriggerProactiveScale()) {
                publishProactiveScale(request, scenarioId);
                totalEvents++;
            }

        } catch (Exception e) {
            log.error("[SIM] scenario failed: scenarioId={} error={}", scenarioId, e.getMessage(), e);
            metrics.incFailure("scenario_execution_error");
            return ScenarioRunResponse.builder()
                    .scenarioId(scenarioId)
                    .scenarioName(request.getScenarioName())
                    .startedAt(startedAt)
                    .completedAt(OffsetDateTime.now())
                    .disasterAlertsCreated(totalAlerts)
                    .residentsCreated(totalResidents)
                    .eventsPublished(totalEvents)
                    .cacheRegenerationTriggered(cacheTriggered)
                    .proactiveScaleTriggered(scaleTriggered)
                    .status("FAILED:" + e.getMessage())
                    .build();
        }

        log.info("[SIM] scenario completed: scenarioId={} alerts={} residents={} events={}",
                scenarioId, totalAlerts, totalResidents, totalEvents);

        return ScenarioRunResponse.builder()
                .scenarioId(scenarioId)
                .scenarioName(request.getScenarioName())
                .startedAt(startedAt)
                .completedAt(OffsetDateTime.now())
                .disasterAlertsCreated(totalAlerts)
                .residentsCreated(totalResidents)
                .eventsPublished(totalEvents)
                .cacheRegenerationTriggered(cacheTriggered)
                .proactiveScaleTriggered(scaleTriggered)
                .status("COMPLETED")
                .build();
    }

    private DisasterAlertRequest buildAlertRequest(ScenarioRunRequest request, boolean triggerProactiveScale) {
        return DisasterAlertRequest.builder()
                .disasterType(request.getDisaster().getDisasterType())
                .region(request.getDisaster().getRegion())
                .level(request.getDisaster().getLevel())
                .count(1)
                .intervalSeconds(0)
                .publishEvents(true)
                .triggerProactiveScale(triggerProactiveScale)
                .scenarioName(request.getScenarioName())
                .build();
    }

    private ResidentBulkRequest buildResidentRequest(ScenarioRunRequest request, String scenarioId) {
        ScenarioRunRequest.ResidentSpec spec = request.getResidents();
        return ResidentBulkRequest.builder()
                .disasterType(request.getDisaster().getDisasterType())
                .region(request.getDisaster().getRegion())
                .residentCount(spec.getCount())
                .distribution(spec.getDistribution())
                .entryStatus("ENTERED")
                .publishEvents(true)
                .scenarioId(scenarioId)
                .scenarioName(request.getScenarioName())
                .build();
    }

    private void publishProactiveScale(ScenarioRunRequest request, String scenarioId) {
        int replicas = request.getScale() != null ? request.getScale().getRequestedReplicas() : 5;
        ProactiveScaleRequestedPayload payload = ProactiveScaleRequestedPayload.builder()
                .disasterType(request.getDisaster().getDisasterType().name())
                .region(request.getDisaster().getRegion())
                .level(request.getDisaster().getLevel().name())
                .requestedReplicas(replicas)
                .build();

        String idempotencyKey = "sim:scale:" + scenarioId + ":" + OffsetDateTime.now().toEpochSecond();
        eventPublisher.publish(EventEnvelope.of("ProactiveScaleRequested", idempotencyKey, payload));
        metrics.incEventsPublished("ProactiveScaleRequested");
    }
}
