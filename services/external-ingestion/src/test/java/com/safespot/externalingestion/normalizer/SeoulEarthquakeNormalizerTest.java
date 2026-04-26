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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeoulEarthquakeNormalizerTest {

    @Mock private DisasterAlertRepository disasterAlertRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private SeoulEarthquakeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SeoulEarthquakeNormalizer(disasterAlertRepo, cacheEventPublisher, metrics, om, new SeoulScopePolicy());
    }

    @Test
    void normalize_seoulAlert_saves() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListEqkEq":{"row":[
              {"OCCR_DT":"2026-04-21 10:05:00","OCCR_PLC":"서울 서초구",
               "MAGNTD_1":"3.2","DEPTH_KM":"8","INTENSITY":"진도2"}
            ]}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(1L);
        given(disasterAlertRepo.save(any())).willReturn(saved);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(disasterAlertRepo).save(argThat(a ->
            "EARTHQUAKE".equals(a.getDisasterType()) &&
            "seoul".equals(a.getRegion()) &&
            "서울 서초구".equals(a.getSourceRegion()) &&
            "INTEREST".equals(a.getLevel()) &&
            Integer.valueOf(1).equals(a.getLevelRank())
        ));
        verify(cacheEventPublisher).publish(any(), eq("disaster-collection"));
    }

    @Test
    void normalize_nonSeoulLocation_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListEqkEq":{"row":[
              {"OCCR_DT":"2026-04-21 11:00:00","OCCR_PLC":"경기도 성남시",
               "MAGNTD_1":"2.0","DEPTH_KM":"5","INTENSITY":"진도1"}
            ]}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
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
