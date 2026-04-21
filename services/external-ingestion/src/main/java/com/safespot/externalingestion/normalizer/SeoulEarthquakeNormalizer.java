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

/**
 * 서울시 지진 발생 현황 정규화 (SEOUL_EARTHQUAKE → disaster_alert)
 *
 * 예상 응답 구조:
 * {"ListEqkEq": {"row": [
 *   {"OCCR_DT": "2026-04-21 10:05:00", "OCCR_PLC": "서울 서초구",
 *    "MAGNTD_1": "3.2", "DEPTH_KM": "8", "INTENSITY": "진도2"}
 * ]}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulEarthquakeNormalizer implements Normalizer {

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
        return "SEOUL_EARTHQUAKE";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path("ListEqkEq").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                try {
                    metrics.incrementDisasterAlertReceived(getSourceCode());
                    OffsetDateTime issuedAt = parseDateTime(row.path("OCCR_DT").asText());

                    if (disasterAlertRepo.existsBySourceAndIssuedAt(getSourceCode(), issuedAt)) {
                        log.debug("[SEOUL_EARTHQUAKE] duplicate issuedAt={} — skip", issuedAt);
                        continue;
                    }

                    DisasterAlert alert = new DisasterAlert();
                    alert.setSource(getSourceCode());
                    alert.setDisasterType("EARTHQUAKE");
                    alert.setRegion(row.path("OCCR_PLC").asText("서울특별시"));
                    alert.setLevel("관심");
                    alert.setMessage("서울시 지진 발생 규모 " + row.path("MAGNTD_1").asText("") +
                        " " + row.path("INTENSITY").asText(""));
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
