package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeoulRiverLevelNormalizerTest {

    private SeoulRiverLevelNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SeoulRiverLevelNormalizer(metrics, om);
    }

    @Test
    void normalize_nonMessageSource_returnsSuccessWithoutCreatingAlert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListRiverStageService":{"row":[
              {"WATG_NM":"한강대교","GU_OFC_NM":"서울특별시 용산구",
               "DTRSM_DATA_CLCT_TM":"2026-04-21 10:00:00",
               "RLTM_RVR_WATL_CNT":"4.5","PLAN_FLDE":"6.5","CNTRL_WATL":"4.0"}
            ]}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(0);
    }

    @Test
    void normalize_malformedJson_returnsFailure() {
        NormalizationResult result = normalizer.normalize(buildRaw("NOT_JSON"));

        assertThat(result.hasFailures()).isTrue();
    }

    private ExternalApiRawPayload buildRaw(String body) {
        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setTraceId("trace-river");
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode("SEOUL_RIVER_LEVEL");
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
