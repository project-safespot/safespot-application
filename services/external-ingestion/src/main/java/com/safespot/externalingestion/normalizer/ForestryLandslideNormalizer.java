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
 * 산림청 산사태 예측정보 정규화 (FORESTRY_LANDSLIDE → disaster_alert)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForestryLandslideNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String EVENT_TYPE = "DisasterDataCollected";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "LANDSLIDE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final SeoulScopePolicy seoulScopePolicy;

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
            String resultCode = root.path("response").path("header").path("resultCode").asText("");
            if (!resultCode.isBlank() && !"00".equals(resultCode)) {
                String resultMsg = root.path("response").path("header").path("resultMsg").asText("");
                log.warn("[FORESTRY_LANDSLIDE] API error raw_id={} resultCode={} resultMsg={}",
                    raw.getRawId(), resultCode, resultMsg);
                return NormalizationResult.failure("API resultCode=" + resultCode + " " + resultMsg);
            }

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode item : items) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    String sourceRegion = item.path("sgg").asText("");

                    if (!seoulScopePolicy.isInScope(sourceRegion)) {
                        log.debug("[FORESTRY_LANDSLIDE] non-Seoul region={} — skip", sourceRegion);
                        continue;
                    }

                    OffsetDateTime issuedAt = parseDateTime(item.path("prctnInfoAnlssDt").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        continue;
                    }

                    String forecastName = item.path("lndslFrcstNm").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("산사태예측정보");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(REGION);
                    alert.setRawLevel(forecastName);
                    alert.setRawLevelTokens(toJsonArray(List.of(forecastName)));
                    alert.setLevel(mapLevel(forecastName));
                    alert.setLevelRank(mapLevelRank(forecastName));
                    alert.setRawCategoryTokens(toJsonArray(List.of("예측정보")));
                    alert.setMessageCategory("ALERT");
                    alert.setMessage("산사태 예측정보 " + forecastName + " — " + sourceRegion);
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);
                    alert.setNormalizationReason(
                        "FORESTRY_LANDSLIDE: lndslFrcstNm=" + forecastName + " → " + alert.getLevel());

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
            metrics.incrementSqsPublish(getSourceCode(), QUEUE, EVENT_TYPE);
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode(), QUEUE, EVENT_TYPE);
            log.error("[FORESTRY_LANDSLIDE] event publish failed — traceId={} collectionType={} region={} alertIds={} completedAt={}",
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, completedAt, e);
        }
    }

    private String mapLevel(String forecastName) {
        if (forecastName == null) return null;
        return switch (forecastName) {
            case "경보" -> "CRITICAL";
            case "주의보" -> "WARNING";
            case "관심" -> "INTEREST";
            default -> null;
        };
    }

    private Integer mapLevelRank(String forecastName) {
        if (forecastName == null) return null;
        return switch (forecastName) {
            case "경보" -> 4;
            case "주의보" -> 3;
            case "관심" -> 1;
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
