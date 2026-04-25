package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import com.safespot.externalingestion.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 서울시 지진 발생 현황 정규화 (SEOUL_EARTHQUAKE → disaster_alert)
 *
 * 예상 응답 구조:
 * {"ListEqkEq": {"row": [
 *   {"OCCR_DT": "2026-04-21 10:05:00", "OCCR_PLC": "서울 서초구",
 *    "MAGNTD_1": "3.2", "DEPTH_KM": "8", "INTENSITY": "진도2"}
 * ]}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulEarthquakeNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String EVENT_TYPE = "DisasterDataCollected";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "EARTHQUAKE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final SeoulScopePolicy seoulScopePolicy;

    @Override
    public String getSourceCode() {
        return "SEOUL_EARTHQUAKE";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        List<Long> affectedAlertIds = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path("ListEqkEq").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    String sourceRegion = row.path("OCCR_PLC").asText("서울특별시");

                    if (!seoulScopePolicy.isInScope(sourceRegion)) {
                        log.debug("[SEOUL_EARTHQUAKE] non-Seoul location={} — skip", sourceRegion);
                        continue;
                    }

                    OffsetDateTime issuedAt = parseDateTime(row.path("OCCR_DT").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        log.debug("[SEOUL_EARTHQUAKE] duplicate issuedAt={} — skip", issuedAt);
                        continue;
                    }

                    String magnitude = row.path("MAGNTD_1").asText("");
                    String intensity = row.path("INTENSITY").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("지진");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(REGION);
                    alert.setRawLevel("기본");
                    alert.setRawLevelTokens(toJsonArray(List.of("기본")));
                    alert.setLevel("INTEREST");
                    alert.setLevelRank(1);
                    alert.setRawCategoryTokens(toJsonArray(List.of("발생")));
                    alert.setMessageCategory("ALERT");
                    alert.setMessage("서울시 지진 발생 규모 " + magnitude + " " + intensity);
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);
                    alert.setNormalizationReason("SEOUL_EARTHQUAKE: 규모=" + magnitude + " — 기본 level INTEREST 적용");

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    affectedAlertIds.add(saved.getAlertId());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                    log.warn("[SEOUL_EARTHQUAKE] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[SEOUL_EARTHQUAKE] parse failed raw_id={}", raw.getRawId(), e);
        }

        if (!affectedAlertIds.isEmpty()) {
            List<Long> capturedIds = List.copyOf(affectedAlertIds);
            String traceId = raw.getExecutionLog().getTraceId();
            String completedAt = OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            AfterCommit.run(() -> publishEvent(traceId, capturedIds, completedAt));
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private void publishEvent(String traceId, List<Long> affectedAlertIds, String completedAt) {
        try {
            DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, false, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(getSourceCode(), QUEUE, EVENT_TYPE);
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode(), QUEUE, EVENT_TYPE);
            log.error("[SEOUL_EARTHQUAKE] event publish failed — traceId={} collectionType={} region={} alertIds={} completedAt={}",
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, completedAt, e);
        }
    }

    private OffsetDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FMT).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(KST);
        }
    }

    private String toJsonArray(List<String> tokens) {
        try {
            return objectMapper.writeValueAsString(tokens);
        } catch (Exception e) {
            return "[]";
        }
    }
}
