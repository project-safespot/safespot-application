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
 * 산림청 산사태 예측정보 정규화.
 * 현재 계약에서는 raw payload 수집만 유지하고 disaster_alert에는 적재하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForestryLandslideNormalizer implements Normalizer {

    private static final String SOURCE_CLASSIFICATION = "DISASTER_FORECAST";

    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "FORESTRY_LANDSLIDE";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
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
            if (items.isMissingNode() || items.isEmpty()) {
                return NormalizationResult.success(0);
            }

            log.info(
                "[FORESTRY_LANDSLIDE] raw_id={} source_classification={} records={} -> skip disaster_alert normalization; raw payload and execution log retained. TODO future table: landslide_forecast",
                raw.getRawId(), SOURCE_CLASSIFICATION, items.size());
            return NormalizationResult.success(0);
        } catch (Exception e) {
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[FORESTRY_LANDSLIDE] parse failed raw_id={}", raw.getRawId(), e);
            return NormalizationResult.failure(e.getMessage());
        }
    }
}
