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
class SeoulRiverLevelNormalizerTest {

    @Mock private DisasterAlertRepository disasterAlertRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private SeoulRiverLevelNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SeoulRiverLevelNormalizer(disasterAlertRepo, cacheEventPublisher, metrics, om, new SeoulScopePolicy());
    }

    @Test
    void normalize_seoulAlertLevel_saves() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListStnWaterLevelEntry":{"row":[
              {"STATION":"한강대교","STD_DT":"2026-04-21 10:00:00",
               "WATER_LEVEL":"4.5","WRNLEVEL_NM":"경계","GU_NM":"서울특별시"}
            ]}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(1L);
        given(disasterAlertRepo.save(any())).willReturn(saved);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(disasterAlertRepo).save(argThat(a ->
            "FLOOD".equals(a.getDisasterType()) &&
            "seoul".equals(a.getRegion()) &&
            "서울특별시".equals(a.getSourceRegion()) &&
            "WARNING".equals(a.getLevel()) &&
            Integer.valueOf(3).equals(a.getLevelRank())
        ));
        verify(cacheEventPublisher).publish(any(), eq("disaster-collection"));
    }

    @Test
    void normalize_belowAlertLevel_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListStnWaterLevelEntry":{"row":[
              {"STATION":"한강대교","STD_DT":"2026-04-21 10:00:00",
               "WATER_LEVEL":"1.2","WRNLEVEL_NM":"관심","GU_NM":"서울특별시"}
            ]}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_nonSeoulRegion_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"ListStnWaterLevelEntry":{"row":[
              {"STATION":"한강대교","STD_DT":"2026-04-21 10:00:00",
               "WATER_LEVEL":"4.5","WRNLEVEL_NM":"경계","GU_NM":"경기도 하남시"}
            ]}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
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
