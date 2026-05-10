package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.SimDisasterAlert;
import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.domain.enums.DisasterLevel;
import com.safespot.scenariosimulator.domain.enums.DisasterType;
import com.safespot.scenariosimulator.dto.request.DisasterAlertRequest;
import com.safespot.scenariosimulator.dto.response.DisasterAlertResponse;
import com.safespot.scenariosimulator.event.EventEnvelope;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.event.payload.CacheRegenerationRequestedPayload;
import com.safespot.scenariosimulator.event.payload.DisasterAlertCreatedPayload;
import com.safespot.scenariosimulator.event.payload.ProactiveScaleRequestedPayload;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimDisasterAlertRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisasterAlertSimulatorService {

    private static final String SOURCE_SIMULATOR = "SIMULATOR";
    private static final int DEFAULT_REPLICAS_FOR_SCALE = 10;
    private static final DateTimeFormatter SCENARIO_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SimDisasterAlertRepository disasterAlertRepository;
    private final TestScenarioRecordRepository scenarioRecordRepository;
    private final SimulatorEventPublisher eventPublisher;
    private final SimulatorMetrics metrics;

    @Transactional
    public DisasterAlertResponse createAlerts(DisasterAlertRequest request, String scenarioId) {
        String scenarioName = resolveScenarioName(request);

        log.info("[SIM] creating disaster alerts: scenarioId={} scenarioName={} type={} region={} level={} count={}",
                scenarioId, scenarioName, request.getDisasterType(), request.getRegion(), request.getLevel(), request.getCount());

        List<Long> alertIds = new ArrayList<>();
        int eventsPublished = 0;

        for (int i = 0; i < request.getCount(); i++) {
            SimDisasterAlert alert = buildAlert(request, i);
            SimDisasterAlert saved = disasterAlertRepository.save(alert);
            alertIds.add(saved.getAlertId());

            trackRecord(scenarioId, scenarioName, "disaster_alert", saved.getAlertId());

            if (request.isPublishEvents()) {
                eventsPublished += publishAlertEvents(saved, request);
            }
        }

        if (request.isTriggerProactiveScale() && request.isPublishEvents()) {
            publishProactiveScale(request);
            eventsPublished++;
        }

        metrics.incDisasterAlertsCreated(alertIds.size());

        log.info("[SIM] disaster alerts created: scenarioId={} count={} eventsPublished={}",
                scenarioId, alertIds.size(), eventsPublished);

        return DisasterAlertResponse.builder()
                .scenarioId(scenarioId)
                .requestedCount(request.getCount())
                .createdCount(alertIds.size())
                .alertIds(alertIds)
                .eventsPublished(eventsPublished)
                .build();
    }

    private String resolveScenarioName(DisasterAlertRequest request) {
        if (request.getScenarioName() != null && !request.getScenarioName().isBlank()) {
            return request.getScenarioName();
        }

        return sanitizeScenarioName("DISASTER_ALERTS_"
                + request.getDisasterType() + "_"
                + request.getRegion() + "_"
                + OffsetDateTime.now().format(SCENARIO_NAME_TIMESTAMP));
    }

    private String sanitizeScenarioName(String scenarioName) {
        return scenarioName.replaceAll("\\s+", "_");
    }

    private SimDisasterAlert buildAlert(DisasterAlertRequest request, int index) {
        OffsetDateTime issuedAt = OffsetDateTime.now().plusNanos((long) index * 1_000_000);
        DisasterLevel level = request.getLevel();
        DisasterType disasterType = request.getDisasterType();

        return SimDisasterAlert.builder()
                .rawType(disasterType.name())
                .disasterType(disasterType.name())
                .rawCategoryTokens("[\"" + disasterType.name() + "\"]")
                .messageCategory("ALERT")
                .rawLevel(level.name())
                .rawLevelTokens("[\"" + level.name() + "\"]")
                .level(level.canonicalLevel)
                .levelRank(level.rank)
                .region(request.getRegion().toLowerCase())
                .sourceRegion(request.getRegion())
                .message("[SIMULATOR] " + disasterType.name() + " " + level.name()
                        + " alert for " + request.getRegion() + " #" + (index + 1))
                .source(SOURCE_SIMULATOR)
                .issuedAt(issuedAt)
                .isInScope(true)
                .normalizationReason("simulator-generated")
                .build();
    }

    private int publishAlertEvents(SimDisasterAlert alert, DisasterAlertRequest request) {
        int count = 0;

        DisasterAlertCreatedPayload payload = DisasterAlertCreatedPayload.builder()
                .alertId(alert.getAlertId())
                .disasterType(alert.getDisasterType())
                .region(alert.getRegion())
                .level(alert.getLevel())
                .levelRank(alert.getLevelRank())
                .issuedAt(alert.getIssuedAt())
                .source(alert.getSource())
                .build();

        String idempotencyKey = "sim:alert:" + alert.getAlertId() + ":CREATED";
        eventPublisher.publish(EventEnvelope.of("DisasterAlertCreated", idempotencyKey, payload));
        count++;

        CacheRegenerationRequestedPayload cachePayload = buildCachePayload(
                "disaster:messages:list:" + alert.getRegion(), "disaster_messages_list");
        String cacheKey = "cache-regen:sim:" + alert.getAlertId() + ":" + OffsetDateTime.now().toEpochSecond();
        eventPublisher.publish(EventEnvelope.of("CacheRegenerationRequested", cacheKey, cachePayload));
        count++;

        return count;
    }

    private void publishProactiveScale(DisasterAlertRequest request) {
        ProactiveScaleRequestedPayload payload = ProactiveScaleRequestedPayload.builder()
                .disasterType(request.getDisasterType().name())
                .region(request.getRegion())
                .level(request.getLevel().name())
                .requestedReplicas(DEFAULT_REPLICAS_FOR_SCALE)
                .build();

        String idempotencyKey = "sim:scale:" + request.getDisasterType() + ":" + OffsetDateTime.now().toEpochSecond();
        eventPublisher.publish(EventEnvelope.of("ProactiveScaleRequested", idempotencyKey, payload));
    }

    private CacheRegenerationRequestedPayload buildCachePayload(String cacheKey, String family) {
        return CacheRegenerationRequestedPayload.builder()
                .cacheKey(cacheKey)
                .cacheKeyFamily(family)
                .requestedAt(OffsetDateTime.now())
                .reason("simulator_triggered")
                .schemaVersion(1)
                .build();
    }

    private void trackRecord(String scenarioId, String scenarioName, String targetTable, Long targetId) {
        scenarioRecordRepository.save(TestScenarioRecord.builder()
                .scenarioId(scenarioId)
                .scenarioName(scenarioName)
                .targetTable(targetTable)
                .targetId(targetId)
                .build());
    }
}
