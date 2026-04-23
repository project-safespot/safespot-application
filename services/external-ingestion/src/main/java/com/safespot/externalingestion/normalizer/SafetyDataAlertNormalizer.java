package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.DisasterAlertCacheRefreshEvent;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import com.safespot.externalingestion.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${ingestion.sqs.disaster-cache-queue-url:}")
    private String disasterQueueUrl;

    @Override
    public String getSourceCode() {
        return "SAFETY_DATA_ALERT";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items").path("item");

            if (items.isMissingNode() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            for (JsonNode item : items) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    DisasterAlert alert = parseAlert(item);

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(alert.getSource(), alert.getIssuedAt())) {
                        log.debug("[SAFETY_DATA_ALERT] duplicate source={} issuedAt={} — skip",
                            alert.getSource(), alert.getIssuedAt());
                        continue;
                    }

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    String traceId = raw.getExecutionLog().getTraceId();
                    AfterCommit.run(() -> publishCacheEvent(saved, traceId));
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

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private DisasterAlert parseAlert(JsonNode item) {
        DisasterAlert alert = new DisasterAlert();
        alert.setSource(getSourceCode());
        alert.setMessage(item.path("MSG_CN").asText(""));
        alert.setRegion(item.path("RCPTN_RGN_NM").asText("서울특별시"));
        alert.setLevel(mapLevel(item.path("EMRG_STEP_NM").asText("관심")));
        alert.setDisasterType(mapDisasterType(item.path("DST_SE_NM").asText("")));
        alert.setIssuedAt(parseDateTime(item.path("CRT_DT").asText()));
        return alert;
    }

    private String mapLevel(String raw) {
        return switch (raw) {
            case "심각" -> "심각";
            case "경계" -> "경계";
            case "주의" -> "주의";
            default -> "관심";
        };
    }

    private String mapDisasterType(String raw) {
        return switch (raw) {
            case "홍수" -> "FLOOD";
            case "산사태" -> "LANDSLIDE";
            default -> "EARTHQUAKE";
        };
    }

    private OffsetDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FMT).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(KST);
        }
    }

    private void publishCacheEvent(DisasterAlert alert, String traceId) {
        try {
            cacheEventPublisher.publish(
                new DisasterAlertCacheRefreshEvent(traceId, alert.getAlertId(), alert.getRegion(), alert.getDisasterType()),
                disasterQueueUrl
            );
            metrics.incrementSqsPublish(getSourceCode());
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode());
            log.error("[SAFETY_DATA_ALERT] cache event publish failed alertId={}", alert.getAlertId(), e);
        }
    }

    private String classifyError(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("parse")) return "parse_error";
        return "validation_error";
    }
}
