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
 * 서울시 하천 수위 정규화 (SEOUL_RIVER_LEVEL → disaster_alert)
 * 경계 이상 수위만 재난 알림으로 적재 (주의/경계/심각)
 *
 * 예상 응답:
 * {"ListStnWaterLevelEntry": {"row": [
 *   {"STATION":"한강대교","STD_DT":"2026-04-21 10:00:00",
 *    "WATER_LEVEL":"4.5","WRNLEVEL_NM":"경계","GU_NM":"서울특별시"}
 * ]}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulRiverLevelNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "FLOOD";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> ALERT_LEVELS = List.of("주의", "경계", "심각");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

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
            JsonNode rows = root.path("ListStnWaterLevelEntry").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                String rawLevelStr = row.path("WRNLEVEL_NM").asText("");
                if (!ALERT_LEVELS.contains(rawLevelStr)) continue;

                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    OffsetDateTime issuedAt = parseDateTime(row.path("STD_DT").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        continue;
                    }

                    String station = row.path("STATION").asText("미상");
                    String waterLevel = row.path("WATER_LEVEL").asText("");
                    String sourceRegion = row.path("GU_NM").asText("서울특별시");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("하천수위");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(sourceRegion);
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
                        "SEOUL_RIVER_LEVEL: WRNLEVEL_NM=" + rawLevelStr + " → " + alert.getLevel());

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
            metrics.incrementSqsPublish(getSourceCode());
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode());
            log.error("[SEOUL_RIVER_LEVEL] event publish failed alertIds={}", affectedAlertIds, e);
        }
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

    private String toJsonArray(List<String> tokens) {
        try {
            return objectMapper.writeValueAsString(tokens);
        } catch (Exception e) {
            return "[]";
        }
    }
}
