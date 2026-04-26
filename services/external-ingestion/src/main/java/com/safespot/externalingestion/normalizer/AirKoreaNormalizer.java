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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 에어코리아 미세먼지 예보 정규화 (AIR_KOREA_AIR_QUALITY → air_quality_log)
 * API: getMinuDustFrcstDspth (미세먼지 예보 조회)
 * informGrade에서 서울 등급을 추출해 pm10Grade/pm25Grade에 저장.
 * 수치(pm10, pm25, o3)는 예보 API에서 제공하지 않으므로 null.
 *
 * 예상 응답:
 * {"response": {"body": {"items": {"item": [
 *   {"dataTime": "2026-04-21 18:00", "informCode": "PM10",
 *    "informGrade": "서울 : 보통, 인천 : 보통, 경기남부 : 보통"}
 * ]}}}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AirKoreaNormalizer implements Normalizer {

    private static final String QUEUE = "environment-collection";
    private static final String REGION = "seoul";
    private static final String COLLECTION_TYPE = "AIR_QUALITY";
    private static final String STATION_SEOUL = "서울";
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
            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || !items.isArray() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            for (JsonNode item : items) {
                try {
                    String informCode = item.path("informCode").asText("");
                    String informGrade = item.path("informGrade").asText("");
                    String seoulGrade = extractSeoulGrade(informGrade);
                    if (seoulGrade == null) {
                        log.debug("[AIR_KOREA] no Seoul grade in informGrade — skip");
                        continue;
                    }

                    OffsetDateTime measuredAt = parseDateTime(item.path("dataTime").asText());

                    if (airQualityLogRepo.existsByStationNameAndMeasuredAt(STATION_SEOUL, measuredAt)) {
                        log.debug("[AIR_KOREA] duplicate station={} measuredAt={} — skip", STATION_SEOUL, measuredAt);
                        continue;
                    }

                    AirQualityLog aql = new AirQualityLog();
                    aql.setStationName(STATION_SEOUL);
                    aql.setMeasuredAt(measuredAt);
                    if ("PM10".equals(informCode)) aql.setPm10Grade(seoulGrade);
                    else if ("PM25".equals(informCode)) aql.setPm25Grade(seoulGrade);

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

    // informGrade 예: "서울 : 보통, 인천 : 보통, 경기남부 : 나쁨"
    private String extractSeoulGrade(String informGrade) {
        if (informGrade == null || informGrade.isBlank()) return null;
        for (String segment : informGrade.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.startsWith(STATION_SEOUL + " :") || trimmed.startsWith(STATION_SEOUL + ":")) {
                int colon = trimmed.indexOf(':');
                if (colon >= 0) {
                    return trimmed.substring(colon + 1).trim();
                }
            }
        }
        return null;
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
}
