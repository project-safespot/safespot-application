package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.DisasterAlertCacheRefreshEvent;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
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
import java.util.Map;

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

    private final DisasterAlertRepository disasterAlertRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final List<String> ALERT_LEVELS = List.of("주의", "경계", "심각");

    @Value("${ingestion.sqs.disaster-cache-queue-url:}")
    private String disasterQueueUrl;

    @Override
    public String getSourceCode() {
        return "SEOUL_RIVER_LEVEL";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path("ListStnWaterLevelEntry").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                String levelRaw = row.path("WRNLEVEL_NM").asText("");
                if (!ALERT_LEVELS.contains(levelRaw)) continue; // 정상 수위 skip

                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    OffsetDateTime issuedAt = parseDateTime(row.path("STD_DT").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        continue;
                    }

                    String station = row.path("STATION").asText("미상");
                    String waterLevel = row.path("WATER_LEVEL").asText("");

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setDisasterType("FLOOD");
                    alert.setRegion(row.path("GU_NM").asText("서울특별시"));
                    alert.setLevel(levelRaw);
                    alert.setMessage(station + " 수위 " + waterLevel + "m " + levelRaw + " 발령");
                    alert.setIssuedAt(issuedAt);

                    DisasterAlert saved = disasterAlertRepo.save(alert);
                    metrics.incrementNormalizationSuccess(getSourceCode());

                    cacheEventPublisher.publish(
                        new DisasterAlertCacheRefreshEvent(raw.getExecutionLog().getTraceId(),
                            saved.getAlertId(), saved.getRegion(), saved.getDisasterType()),
                        disasterQueueUrl
                    );
                    metrics.incrementSqsPublish(getSourceCode());
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
        }
        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private OffsetDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DT_FMT).atZone(KST).toOffsetDateTime();
        } catch (Exception e) {
            return OffsetDateTime.now(KST);
        }
    }
}
