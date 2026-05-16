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

class SeoulEarthquakeNormalizerTest {

    private SeoulEarthquakeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SeoulEarthquakeNormalizer(metrics, om);
    }

    @Test
    void normalize_nonMessageSource_returnsSuccessWithoutCreatingAlert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"TbEqkKenvinfo":{"row":[
              {"OCCR_DT":"2026-04-21 10:05:00","OCCR_PLC":"서울 서초구",
               "MAGNTD_1":"3.2","DEPTH_KM":"8","INTENSITY":"진도2"}
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
        execLog.setTraceId("trace-seoul-eq");
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode("SEOUL_EARTHQUAKE");
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
