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
import java.util.*;

/**
 * 행정안전부 재난문자 정규화 (SAFETY_DATA_ALERT → disaster_alert)
 *
 * 예상 응답 구조:
 * {"response": {"body": {"items": {"item": [
 *   {"MSG_CN": "...", "RCPTN_RGN_NM": "서울특별시", "EMRG_STEP_NM": "주의",
 *    "DST_SE_NM": "홍수", "CRT_DT": "2026-04-21 10:00:00"}
 * ]}}}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafetyDataAlertNormalizer implements Normalizer {

    private static final String QUEUE = "disaster-collection";
    private static final String REGION = "seoul";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> CLEAR_TOKENS =
        List.of("해제", "정상화", "복구", "진화 완료", "통제 해제", "완료");
    private static final List<String> ALERT_TOKENS =
        List.of("발령", "발표", "발효", "예비특보", "주의보", "경보", "위기경보", "발생", "위험", "우려");
    private static final List<String> GUIDANCE_TOKENS =
        List.of("대피", "자제", "금지", "통제", "우회", "확인", "준수", "협조", "유의");

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "SAFETY_DATA_ALERT";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        Map<String, List<Long>> alertsByType = new LinkedHashMap<>();

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isMissingNode() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            for (JsonNode item : items) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    String rawType = item.path("DST_SE_NM").asText("");
                    String disasterType = mapDisasterType(rawType);

                    if (disasterType == null) {
                        log.debug("[SAFETY_DATA_ALERT] out-of-scope rawType={} — skip", rawType);
                        continue;
                    }

                    String sourceRegion = item.path("RCPTN_RGN_NM").asText("서울특별시");
                    if (!sourceRegion.contains("서울")) {
                        log.debug("[SAFETY_DATA_ALERT] non-Seoul region={} — skip", sourceRegion);
                        continue;
                    }

                    OffsetDateTime issuedAt = parseDateTime(item.path("CRT_DT").asText());
                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        log.debug("[SAFETY_DATA_ALERT] duplicate source={} issuedAt={} — skip",
                            getSourceCode(), issuedAt);
                        continue;
                    }

                    String rawLevelStr = item.path("EMRG_STEP_NM").asText("");
                    String message = item.path("MSG_CN").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setRawType(rawType);
                    alert.setDisasterType(disasterType);
                    alert.setSourceRegion(sourceRegion);
                    alert.setRegion(sourceRegion);
                    alert.setRawLevel(rawLevelStr);
                    alert.setRawLevelTokens(toJsonArray(List.of(rawLevelStr)));
                    alert.setLevel(mapLevel(rawLevelStr));
                    alert.setLevelRank(mapLevelRank(rawLevelStr));
                    alert.setMessage(message);
                    alert.setIssuedAt(issuedAt);
                    alert.setIsInScope(true);

                    List<String> catTokens = extractCategoryTokens(message);
                    alert.setRawCategoryTokens(toJsonArray(catTokens));
                    alert.setMessageCategory(deriveCategory(message));
                    alert.setNormalizationReason(
                        "DST_SE_NM=" + rawType + " → " + disasterType +
                        "; EMRG_STEP_NM=" + rawLevelStr + " → " + alert.getLevel());

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    alertsByType.computeIfAbsent(disasterType, k -> new ArrayList<>()).add(saved.getAlertId());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), classifyError(e));
                    log.warn("[SAFETY_DATA_ALERT] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[SAFETY_DATA_ALERT] parse failed raw_id={}", raw.getRawId(), e);
        }

        if (!alertsByType.isEmpty()) {
            String traceId = raw.getExecutionLog().getTraceId();
            String completedAt = OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            alertsByType.forEach((type, ids) -> {
                List<Long> capturedIds = List.copyOf(ids);
                AfterCommit.run(() -> publishEvent(traceId, type, capturedIds, completedAt));
            });
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private void publishEvent(String traceId, String collectionType,
                              List<Long> affectedAlertIds, String completedAt) {
        try {
            DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
                traceId, collectionType, REGION, affectedAlertIds, false, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(getSourceCode());
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode());
            log.error("[SAFETY_DATA_ALERT] event publish failed collectionType={} alertIds={}",
                collectionType, affectedAlertIds, e);
        }
    }

    private String mapDisasterType(String rawType) {
        if (rawType == null) return null;
        return switch (rawType) {
            case "지진", "지진해일", "여진" -> "EARTHQUAKE";
            case "홍수", "태풍", "침수", "호우" -> "FLOOD";
            case "산사태" -> "LANDSLIDE";
            default -> null;
        };
    }

    private String mapLevel(String rawLevel) {
        if (rawLevel == null) return null;
        return switch (rawLevel) {
            case "심각" -> "CRITICAL";
            case "경계" -> "WARNING";
            case "주의" -> "CAUTION";
            case "관심" -> "INTEREST";
            default -> null;
        };
    }

    private Integer mapLevelRank(String rawLevel) {
        if (rawLevel == null) return null;
        return switch (rawLevel) {
            case "심각" -> 4;
            case "경계" -> 3;
            case "주의" -> 2;
            case "관심" -> 1;
            default -> null;
        };
    }

    private String deriveCategory(String message) {
        if (message == null) return "ALERT";
        boolean hasClear = CLEAR_TOKENS.stream().anyMatch(message::contains);
        if (hasClear) return "CLEAR";
        boolean hasAlert = ALERT_TOKENS.stream().anyMatch(message::contains);
        if (hasAlert) return "ALERT";
        boolean hasGuidance = GUIDANCE_TOKENS.stream().anyMatch(message::contains);
        if (hasGuidance) return "GUIDANCE";
        return "ALERT";
    }

    private List<String> extractCategoryTokens(String message) {
        if (message == null) return List.of();
        List<String> found = new ArrayList<>();
        for (String t : CLEAR_TOKENS) if (message.contains(t)) found.add(t);
        for (String t : ALERT_TOKENS) if (message.contains(t)) found.add(t);
        for (String t : GUIDANCE_TOKENS) if (message.contains(t)) found.add(t);
        return found;
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

    private String classifyError(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("parse")) return "parse_error";
        return "validation_error";
    }
}
