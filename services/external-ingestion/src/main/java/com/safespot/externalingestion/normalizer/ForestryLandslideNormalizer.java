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
 * 산림청 산사태 위험 예측 정규화 (FORESTRY_LANDSLIDE → disaster_alert)
 * NOTE: 산림청 인증키 승인 대기 중 — ForestryLandslideHandler.isEnabled()=false
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForestryLandslideNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "LANDSLIDE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "FORESTRY_LANDSLIDE";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        List<Long> affectedAlertIds = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode item : items) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    OffsetDateTime issuedAt = parseDateTime(item.path("STD_DT").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        continue;
                    }

                    String riskGrade = item.path("RISK_GRADE").asText("");
                    String sourceRegion = item.path("PRV_AREA_NM").asText("서울특별시");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("산사태위험");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(sourceRegion);
                    alert.setRawLevel(riskGrade);
                    alert.setRawLevelTokens(toJsonArray(List.of(riskGrade)));
                    alert.setLevel(mapLevel(riskGrade));
                    alert.setLevelRank(mapLevelRank(riskGrade));
                    alert.setRawCategoryTokens(toJsonArray(List.of("위험")));
                    alert.setMessageCategory("ALERT");
                    alert.setMessage("산사태 위험 " + riskGrade + " — " + sourceRegion);
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);
                    alert.setNormalizationReason(
                        "FORESTRY_LANDSLIDE: RISK_GRADE=" + riskGrade + " → " + alert.getLevel());

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    affectedAlertIds.add(saved.getAlertId());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                    log.warn("[FORESTRY_LANDSLIDE] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[FORESTRY_LANDSLIDE] parse failed raw_id={}", raw.getRawId(), e);
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
            log.error("[FORESTRY_LANDSLIDE] event publish failed alertIds={}", affectedAlertIds, e);
        }
    }

    private String mapLevel(String riskGrade) {
        if (riskGrade == null) return null;
        return switch (riskGrade) {
            case "5등급", "4등급" -> "CRITICAL";
            case "3등급" -> "WARNING";
            case "2등급" -> "CAUTION";
            case "1등급" -> "INTEREST";
            default -> null;
        };
    }

    private Integer mapLevelRank(String riskGrade) {
        if (riskGrade == null) return null;
        return switch (riskGrade) {
            case "5등급", "4등급" -> 4;
            case "3등급" -> 3;
            case "2등급" -> 2;
            case "1등급" -> 1;
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
