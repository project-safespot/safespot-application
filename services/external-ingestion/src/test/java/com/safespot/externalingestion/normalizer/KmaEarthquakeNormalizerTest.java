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

class KmaEarthquakeNormalizerTest {

    private KmaEarthquakeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new KmaEarthquakeNormalizer(metrics, om);
    }

    @Test
    void normalize_nonMessageSource_returnsSuccessWithoutCreatingAlertOrDetail() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
              {"tmFc":"202604211000","tmEqk":"20260421095800",
               "lat":"37.56","lon":"126.97","loc":"서울 북쪽 10km",
               "mt":"3.5","inT":"최대진도III"}
            ]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(0);
    }

    @Test
    void normalize_apiError_returnsFailure() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void normalize_malformedJson_returnsFailure() {
        NormalizationResult result = normalizer.normalize(buildRaw("NOT_JSON"));

        assertThat(result.hasFailures()).isTrue();
    }

    private ExternalApiRawPayload buildRaw(String body) {
        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setTraceId("trace-kma-eq");
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode("KMA_EARTHQUAKE");
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
