package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SafetyDataAlertNormalizerTest {

    @Mock private DisasterAlertRepository disasterAlertRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private SafetyDataAlertNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SafetyDataAlertNormalizer(disasterAlertRepo, cacheEventPublisher, metrics, om);
    }

    @Test
    void normalize_validPayload_savesDisasterAlert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"서울 홍수 경보","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"주의","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 10:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(1L);
        saved.setRegion("서울특별시");
        saved.setDisasterType("FLOOD");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        verify(disasterAlertRepo).save(argThat(a ->
            "FLOOD".equals(a.getDisasterType()) &&
            "SAFETY_DATA_ALERT".equals(a.getSource()) &&
            "주의".equals(a.getLevel())
        ));
        verify(cacheEventPublisher).publish(any(), any());
    }

    @Test
    void normalize_duplicatePayload_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"중복","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"관심","DST_SE_NM":"지진","CRT_DT":"2026-04-21 09:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(true);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
    }

    @Test
    void normalize_emptyItems_returnsZeroSuccess() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(0);
    }

    @Test
    void normalize_malformedJson_returnsFailure() {
        ExternalApiRawPayload raw = buildRaw("NOT_JSON");

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.hasFailures()).isTrue();
        verify(disasterAlertRepo, never()).save(any());
    }

    @Test
    void normalize_disasterTypeMapping() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"산사태 경보","RCPTN_RGN_NM":"서울 관악구",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"산사태","CRT_DT":"2026-04-21 11:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any())).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(2L);
        saved.setRegion("서울 관악구");
        saved.setDisasterType("LANDSLIDE");
        given(disasterAlertRepo.save(any())).willReturn(saved);

        normalizer.normalize(raw);

        verify(disasterAlertRepo).save(argThat(a -> "LANDSLIDE".equals(a.getDisasterType())));
    }

    private ExternalApiRawPayload buildRaw(String body) {
        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setTraceId("test-trace-id");

        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode("SAFETY_DATA_ALERT");

        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
