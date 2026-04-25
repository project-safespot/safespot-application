package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class SafetyDataAlertNormalizerTest {

    @Mock private DisasterAlertRepository disasterAlertRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private SafetyDataAlertNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new SafetyDataAlertNormalizer(disasterAlertRepo, cacheEventPublisher, metrics, om, new SeoulScopePolicy());
    }

    @Test
    void normalize_validPayload_savesDisasterAlert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"서울 홍수 경보 발령","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"주의","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 10:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(1L);
        saved.setRegion("seoul");
        saved.setDisasterType("FLOOD");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);
        verify(disasterAlertRepo).save(argThat(a ->
            "FLOOD".equals(a.getDisasterType()) &&
            "SAFETY_DATA_ALERT".equals(a.getSource()) &&
            "CAUTION".equals(a.getLevel()) &&
            Integer.valueOf(2).equals(a.getLevelRank()) &&
            Boolean.TRUE.equals(a.getIsInScope()) &&
            "홍수".equals(a.getRawType()) &&
            "seoul".equals(a.getRegion()) &&
            "서울특별시".equals(a.getSourceRegion())
        ));
        verify(cacheEventPublisher).publish(any(), eq("disaster-collection"));
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
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_emptyItems_returnsZeroSuccess() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(0);
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_malformedJson_returnsFailure() {
        ExternalApiRawPayload raw = buildRaw("NOT_JSON");

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.hasFailures()).isTrue();
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_disasterTypeMapping() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"산사태 경보 발령","RCPTN_RGN_NM":"서울 관악구",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"산사태","CRT_DT":"2026-04-21 11:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any())).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(2L);
        saved.setRegion("seoul");
        saved.setDisasterType("LANDSLIDE");
        given(disasterAlertRepo.save(any())).willReturn(saved);

        normalizer.normalize(raw);

        verify(disasterAlertRepo).save(argThat(a ->
            "LANDSLIDE".equals(a.getDisasterType()) &&
            "WARNING".equals(a.getLevel()) &&
            Integer.valueOf(3).equals(a.getLevelRank())
        ));
    }

    @Test
    void normalize_outOfScopeType_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"폭염 주의","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"주의","DST_SE_NM":"폭염","CRT_DT":"2026-04-21 12:00:00"}
            ]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_nonSeoulRegion_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"홍수 경보","RCPTN_RGN_NM":"부산광역시",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 13:00:00"}
            ]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_mixedTypes_publishesOneEvent() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"지진 발생","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"주의","DST_SE_NM":"지진","CRT_DT":"2026-04-21 10:00:00"},
              {"MSG_CN":"홍수 경보 발령","RCPTN_RGN_NM":"서울 강남구",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 10:05:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert eq = new DisasterAlert(); eq.setAlertId(1L); eq.setDisasterType("EARTHQUAKE");
        DisasterAlert fl = new DisasterAlert(); fl.setAlertId(2L); fl.setDisasterType("FLOOD");
        given(disasterAlertRepo.save(any(DisasterAlert.class)))
            .willReturn(eq)
            .willReturn(fl);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(2);
        // ONE event per run, not per type
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(cacheEventPublisher, times(1)).publish(any(), eq("disaster-collection"));
        verify(cacheEventPublisher).publish(argThat(e -> {
            DisasterDataCollectedEvent ev = (DisasterDataCollectedEvent) e;
            return "DISASTER".equals(ev.getPayload().getCollectionType()) &&
                   ev.getPayload().getAffectedAlertIds().containsAll(List.of(1L, 2L));
        }), any());
    }

    @Test
    void normalize_clearMessage_hasExpiredAlertsTrue() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"홍수 경보 해제","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"주의","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 14:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(10L);
        saved.setDisasterType("FLOOD");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);

        normalizer.normalize(raw);

        verify(cacheEventPublisher).publish(argThat(e -> {
            DisasterDataCollectedEvent ev = (DisasterDataCollectedEvent) e;
            return ev.getPayload().isHasExpiredAlerts();
        }), any());
    }

    @Test
    void normalize_alertMessage_hasExpiredAlertsFalse() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"지진 발생 위험","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"지진","CRT_DT":"2026-04-21 15:00:00"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(11L);
        saved.setDisasterType("EARTHQUAKE");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);

        normalizer.normalize(raw);

        verify(cacheEventPublisher).publish(argThat(e -> {
            DisasterDataCollectedEvent ev = (DisasterDataCollectedEvent) e;
            return !ev.getPayload().isHasExpiredAlerts();
        }), any());
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
