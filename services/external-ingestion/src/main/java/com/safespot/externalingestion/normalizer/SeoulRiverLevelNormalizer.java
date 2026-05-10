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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 서울시 하천 수위 정규화 (SEOUL_RIVER_LEVEL → disaster_alert) — ListRiverStageService
 * 통제수위 이상만 재난 알림으로 적재한다.
 *
 * 예상 응답 구조:
 * {"ListRiverStageService": {"row": [
 *   {"WATG_NM":"한강대교","GU_OFC_NM":"용산구",
 *    "DTRSM_DATA_CLCT_TM":"2026-04-21 10:00:00",
 *    "RLTM_RVR_WATL_CNT":"4.5","PLAN_FLDE":"6.5","CNTRL_WATL":"4.0"}
 * ]}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulRiverLevelNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String EVENT_TYPE = "DisasterDataCollected";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "FLOOD";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final SeoulScopePolicy seoulScopePolicy;

    @Override
    public String getSourceCode() {
        return "SEOUL_RIVER_LEVEL";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        List<Long> affectedAlertIds = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path("ListRiverStageService").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                String rawLevelStr = resolveAlertLevel(row);
                if (rawLevelStr == null) continue;

                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    String sourceRegion = row.path("GU_OFC_NM").asText("서울특별시");

                    if (!seoulScopePolicy.isInScope(sourceRegion)) {
                        log.debug("[SEOUL_RIVER_LEVEL] non-Seoul region={} — skip", sourceRegion);
                        continue;
                    }

                    OffsetDateTime issuedAt = parseDateTime(row.path("DTRSM_DATA_CLCT_TM").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        continue;
                    }

                    String station = row.path("WATG_NM").asText("미상");
                    String waterLevel = row.path("RLTM_RVR_WATL_CNT").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("하천수위");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(REGION);
                    alert.setRawLevel(rawLevelStr);
                    alert.setRawLevelTokens(toJsonArray(List.of(rawLevelStr)));
                    alert.setLevel(mapLevel(rawLevelStr));
                    alert.setLevelRank(mapLevelRank(rawLevelStr));
                    alert.setRawCategoryTokens(toJsonArray(List.of("발령")));
                    alert.setMessageCategory("ALERT");
                    alert.setMessage(station + " 수위 " + waterLevel + "m " + rawLevelStr + " 발령");
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);
                    alert.setNormalizationReason(
                        "SEOUL_RIVER_LEVEL: threshold=" + rawLevelStr + " → " + alert.getLevel());

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    affectedAlertIds.add(saved.getAlertId());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                    log.warn("[SEOUL_RIVER_LEVEL] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[SEOUL_RIVER_LEVEL] parse failed raw_id={}", raw.getRawId(), e);
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
            log.error("[SEOUL_RIVER_LEVEL] event publish failed — traceId={} collectionType={} region={} alertIds={} completedAt={}",
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, completedAt, e);
        }
    }

    private String resolveAlertLevel(JsonNode row) {
        BigDecimal current = decimal(row, "RLTM_RVR_WATL_CNT");
        BigDecimal plannedFlood = decimal(row, "PLAN_FLDE");
        BigDecimal control = decimal(row, "CNTRL_WATL");

        if (current == null) return null;
        if (plannedFlood != null && current.compareTo(plannedFlood) >= 0) return "심각";
        if (control != null && current.compareTo(control) >= 0) return "경계";
        return null;
    }

    private String mapLevel(String rawLevel) {
        return switch (rawLevel) {
            case "심각" -> "CRITICAL";
            case "경계" -> "WARNING";
            case "주의" -> "CAUTION";
            default -> null;
        };
    }

    private Integer mapLevelRank(String rawLevel) {
        return switch (rawLevel) {
            case "심각" -> 4;
            case "경계" -> 3;
            case "주의" -> 2;
            default -> null;
        };
    }

    private OffsetDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FMT).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(KST);
        }
    }

    private BigDecimal decimal(JsonNode row, String key) {
        JsonNode node = row.path(key);
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) return node.decimalValue();
        String value = node.asText("").trim();
        if (value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
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
