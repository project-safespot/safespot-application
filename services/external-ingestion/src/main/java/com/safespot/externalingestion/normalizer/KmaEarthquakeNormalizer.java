package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.DisasterAlertDetail;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.repository.DisasterAlertDetailRepository;
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
 * 기상청 지진 정보 정규화 (KMA_EARTHQUAKE → disaster_alert + disaster_alert_detail)
 *
 * 예상 응답 구조:
 * {"response": {"body": {"items": {"item": [
 *   {"tmFc": "202604211000", "tmEqk": "20260421095800",
 *    "lat": "37.56", "lon": "126.97", "loc": "서울 북부 10km",
 *    "mt": "3.5", "inT": "최대진도Ⅱ"}
 * ]}}}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KmaEarthquakeNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String EVENT_TYPE = "DisasterDataCollected";
    private static final String REGION = "seoul";
    private static final String DISASTER_TYPE = "EARTHQUAKE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final DisasterAlertRepository disasterAlertRepo;
    private final DisasterAlertDetailRepository detailRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final SeoulScopePolicy seoulScopePolicy;

    @Override
    public String getSourceCode() {
        return "KMA_EARTHQUAKE";
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
                log.warn("[KMA_EARTHQUAKE] API error raw_id={} resultCode={} resultMsg={}", raw.getRawId(), resultCode, resultMsg);
                return NormalizationResult.failure("API resultCode=" + resultCode + " " + resultMsg);
            }

            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isMissingNode() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            for (JsonNode item : items) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    String sourceRegion = item.path("loc").asText("서울특별시");

                    if (!seoulScopePolicy.isInScope(sourceRegion)) {
                        log.debug("[KMA_EARTHQUAKE] non-Seoul region={} — skip", sourceRegion);
                        continue;
                    }

                    OffsetDateTime issuedAt = parseDateTime(item.path("tmFc").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        log.debug("[KMA_EARTHQUAKE] duplicate issuedAt={} — skip", issuedAt);
                        continue;
                    }

                    String rawLevelStr = item.path("inT").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType("지진");
                    alert.setDisasterType(DISASTER_TYPE);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(REGION);
                    alert.setRawLevel(rawLevelStr);
                    alert.setRawLevelTokens(toJsonArray(List.of(rawLevelStr)));
                    alert.setLevel(mapLevel(rawLevelStr));
                    alert.setLevelRank(mapLevelRank(rawLevelStr));
                    alert.setRawCategoryTokens(toJsonArray(List.of("발생")));
                    alert.setMessageCategory("ALERT");
                    alert.setMessage("지진 발생: " + item.path("loc").asText("") +
                        " 규모 " + item.path("mt").asText("") +
                        " " + item.path("inT").asText(""));
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);
                    alert.setNormalizationReason(
                        "KMA_EARTHQUAKE: inT=" + rawLevelStr + " → " + alert.getLevel());

                    DisasterAlert saved = disasterAlertRepo.save(alert);

                    // detail must be saved atomically with the alert.
                    // If detail fails after alert is persisted, compensate by deleting the alert so
                    // dedup (existsBySourceAndIssuedAt) does not block recovery on the next collection run.
                    try {
                        DisasterAlertDetail detail = buildDetail(item, saved);
                        detailRepo.save(detail);
                    } catch (Exception detailEx) {
                        log.warn("[KMA_EARTHQUAKE] detail save failed alertId={} issuedAt={} — compensating alert delete",
                            saved.getAlertId(), issuedAt, detailEx);
                        disasterAlertRepo.delete(saved);
                        throw detailEx;
                    }

                    metrics.incrementNormalizationSuccess(getSourceCode());
                    affectedAlertIds.add(saved.getAlertId());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                    log.warn("[KMA_EARTHQUAKE] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[KMA_EARTHQUAKE] parse failed raw_id={}", raw.getRawId(), e);
        }

        if (!affectedAlertIds.isEmpty()) {
            List<Long> capturedIds = List.copyOf(affectedAlertIds);
            String traceId = raw.getExecutionLog().getTraceId();
            String completedAt = OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            AfterCommit.run(() -> publishEvent(traceId, capturedIds, completedAt));
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private DisasterAlertDetail buildDetail(JsonNode item, DisasterAlert saved) {
        DisasterAlertDetail detail = new DisasterAlertDetail();
        detail.setAlert(saved);
        detail.setDetailType(DISASTER_TYPE);
        String magStr = item.path("mt").asText("");
        if (!magStr.isBlank()) {
            detail.setMagnitude(new BigDecimal(magStr));
        }
        detail.setEpicenter(item.path("loc").asText(""));
        detail.setIntensity(item.path("inT").asText(""));
        return detail;
    }

    private void publishEvent(String traceId, List<Long> affectedAlertIds, String completedAt) {
        try {
            DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, false, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(getSourceCode(), QUEUE, EVENT_TYPE);
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode(), QUEUE, EVENT_TYPE);
            log.error("[KMA_EARTHQUAKE] event publish failed — traceId={} collectionType={} region={} alertIds={} completedAt={}",
                traceId, DISASTER_TYPE, REGION, affectedAlertIds, completedAt, e);
        }
    }

    private String mapLevel(String rawLevel) {
        if (rawLevel == null) return null;
        return switch (rawLevel) {
            case "심각", "최대진도Ⅶ", "최대진도Ⅷ", "최대진도Ⅸ", "최대진도Ⅹ" -> "CRITICAL";
            case "경계", "최대진도Ⅴ", "최대진도Ⅵ" -> "WARNING";
            case "주의", "최대진도Ⅲ", "최대진도Ⅳ" -> "CAUTION";
            case "관심", "최대진도Ⅰ", "최대진도Ⅱ" -> "INTEREST";
            default -> null;
        };
    }

    private Integer mapLevelRank(String rawLevel) {
        if (rawLevel == null) return null;
        return switch (rawLevel) {
            case "심각", "최대진도Ⅶ", "최대진도Ⅷ", "최대진도Ⅸ", "최대진도Ⅹ" -> 4;
            case "경계", "최대진도Ⅴ", "최대진도Ⅵ" -> 3;
            case "주의", "최대진도Ⅲ", "최대진도Ⅳ" -> 2;
            case "관심", "최대진도Ⅰ", "최대진도Ⅱ" -> 1;
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
