package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.AirQualityLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.EnvironmentDataCollectedEvent;
import com.safespot.externalingestion.repository.AirQualityLogRepository;
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
 * 에어코리아 대기질 정규화 (AIR_KOREA_AIR_QUALITY → air_quality_log)
 *
 * 예상 응답:
 * {"response":{"body":{"items":[
 *   {"stationName":"종로구","dataTime":"2026-04-21 10:00",
 *    "pm10Value":"25","pm10Grade":"좋음","pm25Value":"12","pm25Grade":"좋음",
 *    "o3Value":"0.025","o3Grade":"좋음","khaiValue":"45","khaiGrade":"좋음"}
 * ]}}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AirKoreaNormalizer implements Normalizer {

    private static final String QUEUE = "environment-collection";
    private static final String REGION = "seoul";
    private static final String COLLECTION_TYPE = "AIR_QUALITY";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter WINDOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");

    private final AirQualityLogRepository airQualityLogRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "AIR_KOREA_AIR_QUALITY";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        boolean anyStored = false;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items");
            if (items.isMissingNode() || !items.isArray() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            for (JsonNode item : items) {
                try {
                    String stationName = item.path("stationName").asText();
                    OffsetDateTime measuredAt = parseDateTime(item.path("dataTime").asText());

                    if (airQualityLogRepo.existsByStationNameAndMeasuredAt(stationName, measuredAt)) {
                        log.debug("[AIR_KOREA] duplicate station={} measuredAt={} — skip", stationName, measuredAt);
                        continue;
                    }

                    AirQualityLog aql = new AirQualityLog();
                    aql.setStationName(stationName);
                    aql.setMeasuredAt(measuredAt);
                    aql.setPm10(parseIntSafe(item.path("pm10Value").asText()));
                    aql.setPm10Grade(item.path("pm10Grade").asText(null));
                    aql.setPm25(parseIntSafe(item.path("pm25Value").asText()));
                    aql.setPm25Grade(item.path("pm25Grade").asText(null));
                    aql.setO3(parseDecimalSafe(item.path("o3Value").asText()));
                    aql.setO3Grade(item.path("o3Grade").asText(null));
                    aql.setKhaiValue(parseIntSafe(item.path("khaiValue").asText()));
                    aql.setKhaiGrade(item.path("khaiGrade").asText(null));

                    airQualityLogRepo.save(aql);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    succeeded++;
                    anyStored = true;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                    log.warn("[AIR_KOREA] item normalization failed raw_id={}", raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[AIR_KOREA] parse failed raw_id={}", raw.getRawId(), e);
        }

        if (anyStored) {
            String traceId = raw.getExecutionLog().getTraceId();
            OffsetDateTime now = OffsetDateTime.now(KST);
            String completedAt = now.format(ISO_FMT);
            String timeWindow = now.format(WINDOW_FMT);
            AfterCommit.run(() -> publishEvent(traceId, timeWindow, completedAt));
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private void publishEvent(String traceId, String timeWindow, String completedAt) {
        try {
            EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
                traceId, COLLECTION_TYPE, REGION, timeWindow, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(getSourceCode(), QUEUE, "EnvironmentDataCollected");
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode(), QUEUE, "EnvironmentDataCollected");
            log.error("[AIR_KOREA] event publish failed — traceId={} collectionType={} region={} timeWindow={} completedAt={}",
                traceId, COLLECTION_TYPE, REGION, timeWindow, completedAt, e);
        }
    }

    private OffsetDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FMT).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(KST);
        }
    }

    private Integer parseIntSafe(String val) {
        try {
            return (val == null || val.isBlank() || val.equals("-")) ? null : Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseDecimalSafe(String val) {
        try {
            return (val == null || val.isBlank() || val.equals("-")) ? null : new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
