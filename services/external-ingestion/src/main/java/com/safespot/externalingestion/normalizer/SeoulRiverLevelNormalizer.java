package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서울시 하천 수위 정규화.
 * 현재 계약에서는 raw payload 수집만 유지하고 disaster_alert에는 적재하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeoulRiverLevelNormalizer implements Normalizer {

    private static final String SOURCE_CLASSIFICATION = "FLOOD_RISK_OBSERVATION";

    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "SEOUL_RIVER_LEVEL";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path("ListRiverStageService").path("row");
            if (rows.isMissingNode() || rows.isEmpty()) {
                return NormalizationResult.success(0);
            }

            log.info(
                "[SEOUL_RIVER_LEVEL] raw_id={} source_classification={} records={} -> skip disaster_alert normalization; raw payload and execution log retained. TODO future table: river_level_observation or flood_risk_event",
                raw.getRawId(), SOURCE_CLASSIFICATION, rows.size());
            return NormalizationResult.success(0);
        } catch (Exception e) {
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[SEOUL_RIVER_LEVEL] parse failed raw_id={}", raw.getRawId(), e);
            return NormalizationResult.failure(e.getMessage());
        }
    }
}
