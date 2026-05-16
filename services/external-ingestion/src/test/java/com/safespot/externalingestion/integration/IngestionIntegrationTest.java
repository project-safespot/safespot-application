package com.safespot.externalingestion.integration;

import com.safespot.externalingestion.config.DataInitializer;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.repository.DisasterAlertDetailRepository;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import com.safespot.externalingestion.repository.ExternalApiExecutionLogRepository;
import com.safespot.externalingestion.repository.ExternalApiRawPayloadRepository;
import com.safespot.externalingestion.repository.ExternalApiSourceRepository;
import com.safespot.externalingestion.service.NormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * H2 in-memory DB 기준의 수집 및 정규화 통합 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class IngestionIntegrationTest {

    @Autowired NormalizationService normalizationService;
    @Autowired NormalizationQueue normalizationQueue;
    @Autowired ExternalApiSourceRepository sourceRepo;
    @Autowired ExternalApiRawPayloadRepository rawPayloadRepo;
    @Autowired ExternalApiExecutionLogRepository executionLogRepo;
    @Autowired DisasterAlertRepository disasterAlertRepo;
    @Autowired DisasterAlertDetailRepository disasterAlertDetailRepo;
    @Autowired DataInitializer dataInitializer;
    @MockBean CacheEventPublisher cacheEventPublisher;

    @Test
    void dataInitializer_seedsAllSources() {
        List<ExternalApiSource> sources = sourceRepo.findAll();
        assertThat(sources).hasSizeGreaterThanOrEqualTo(10);

        assertThat(sources).anyMatch(s -> "SAFETY_DATA_ALERT".equals(s.getSourceCode()) && s.isActive());
        assertThat(sources).anyMatch(s -> "FORESTRY_LANDSLIDE".equals(s.getSourceCode())
            && s.isActive()
            && s.getBaseUrl().contains("predictionInfoService/predictionInfoList"));
        assertThat(sources).anyMatch(s -> "SEOUL_SHELTER_EARTHQUAKE".equals(s.getSourceCode())
            && s.isActive()
            && s.getBaseUrl().contains("TlEtqkP"));
        assertThat(sources).anyMatch(s -> "KMA_WEATHER".equals(s.getSourceCode()));
    }

    @Test
    void dataInitializer_reconcilesExistingSourceContract() {
        ExternalApiSource source = sourceRepo.findBySourceCode("SEOUL_SHELTER_EARTHQUAKE").orElseThrow();
        source.setBaseUrl("http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqKkenvinfo/1/1000/");
        source.setActive(false);
        sourceRepo.saveAndFlush(source);

        dataInitializer.run(null);

        ExternalApiSource reconciled = sourceRepo.findBySourceCode("SEOUL_SHELTER_EARTHQUAKE").orElseThrow();
        assertThat(reconciled.isActive()).isTrue();
        assertThat(reconciled.getBaseUrl()).isEqualTo("http://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/1/1000/");
    }

    @Test
    void normalizationService_processesSafetyDataAlert() {
        processSource("SAFETY_DATA_ALERT", """
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"통합테스트 홍수 경보","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 15:00:00"}
            ]}}}}
            """, "integration-test-hash-001", "integration-test-trace");

        List<DisasterAlert> alerts = disasterAlertRepo.findAll();
        assertThat(alerts).anyMatch(a ->
            "FLOOD".equals(a.getDisasterType()) &&
            "SAFETY_DATA_ALERT".equals(a.getSource()) &&
            "WARNING".equals(a.getLevel()) &&
            Integer.valueOf(3).equals(a.getLevelRank()) &&
            Boolean.TRUE.equals(a.getIsInScope()) &&
            "seoul".equals(a.getRegion()) &&
            "서울특별시".equals(a.getSourceRegion())
        );
    }

    @Test
    void normalizationService_duplicateAlert_notInsertedTwice() {
        ExternalApiSource source = sourceRepo.findBySourceCode("SAFETY_DATA_ALERT").orElseThrow();

        var execLog = new com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog();
        execLog.setSource(source);
        execLog.setExecutionStatus(com.safespot.externalingestion.domain.enums.ExecutionStatus.RUNNING);
        execLog.setStartedAt(OffsetDateTime.now());
        execLog.setTraceId("dup-test-trace");
        var savedLog = executionLogRepo.save(execLog);

        String responseBody = """
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"중복 지진","RCPTN_RGN_NM":"서울","EMRG_STEP_NM":"관심",
               "DST_SE_NM":"지진","CRT_DT":"2026-04-21 16:00:00"}
            ]}}}}
            """;

        for (int i = 0; i < 2; i++) {
            ExternalApiRawPayload raw = new ExternalApiRawPayload();
            raw.setSource(source);
            raw.setExecutionLog(savedLog);
            raw.setResponseBody(responseBody);
            raw.setPayloadHash("dup-hash-" + i);
            raw.setCollectedAt(OffsetDateTime.now());
            raw.setRetentionExpiresAt(OffsetDateTime.now().plusDays(90));
            ExternalApiRawPayload savedRaw = rawPayloadRepo.save(raw);

            normalizationService.process(
                NormalizationMessage.of(savedRaw.getRawId(), source.getSourceId(), savedLog.getExecutionId(), "t")
            );
        }

        long count = disasterAlertRepo.findAll().stream()
            .filter(a -> "SAFETY_DATA_ALERT".equals(a.getSource()))
            .filter(a -> a.getIssuedAt().getHour() == 16)
            .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void normalizationService_kmaEarthquake_doesNotCreateDisasterAlertOrDetailOrPublishEvent() {
        processSource("KMA_EARTHQUAKE", """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
              {"tmFc":"202604211000","tmEqk":"20260421095800","lat":"37.56","lon":"126.97",
               "loc":"서울 북쪽 10km","mt":"3.5","inT":"최대진도III"}
            ]}}}}
            """, "kma-eq-hash-001", "kma-eq-trace");

        assertThat(disasterAlertRepo.findAll()).isEmpty();
        assertThat(disasterAlertDetailRepo.findAll()).isEmpty();
        verify(cacheEventPublisher, never()).publish(any(), anyString());
    }

    @Test
    void normalizationService_seoulEarthquake_doesNotCreateDisasterAlertOrPublishEvent() {
        processSource("SEOUL_EARTHQUAKE", """
            {"TbEqkKenvinfo":{"row":[
              {"OCCR_DT":"2026-04-21 10:05:00","OCCR_PLC":"서울 서초구",
               "MAGNTD_1":"3.2","DEPTH_KM":"8","INTENSITY":"진도2"}
            ]}}
            """, "seoul-eq-hash-001", "seoul-eq-trace");

        assertThat(disasterAlertRepo.findAll()).isEmpty();
        verify(cacheEventPublisher, never()).publish(any(), anyString());
    }

    @Test
    void normalizationService_forestryLandslide_doesNotCreateDisasterAlertOrPublishEvent() {
        processSource("FORESTRY_LANDSLIDE", """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[
              {"prctnInfoAnlssDt":"2026-04-21 10:00:00","sgg":"서울특별시 종로구","lndslFrcstNm":"주의보"}
            ]}}}}
            """, "forestry-hash-001", "forestry-trace");

        assertThat(disasterAlertRepo.findAll()).isEmpty();
        verify(cacheEventPublisher, never()).publish(any(), anyString());
    }

    @Test
    void normalizationService_seoulRiverLevel_doesNotCreateDisasterAlertOrPublishEvent() {
        processSource("SEOUL_RIVER_LEVEL", """
            {"ListRiverStageService":{"row":[
              {"WATG_NM":"한강대교","GU_OFC_NM":"서울특별시 용산구",
               "DTRSM_DATA_CLCT_TM":"2026-04-21 10:00:00",
               "RLTM_RVR_WATL_CNT":"4.5","PLAN_FLDE":"6.5","CNTRL_WATL":"4.0"}
            ]}}
            """, "river-hash-001", "river-trace");

        assertThat(disasterAlertRepo.findAll()).isEmpty();
        verify(cacheEventPublisher, never()).publish(any(), anyString());
    }

    private void processSource(String sourceCode, String responseBody, String payloadHash, String traceId) {
        ExternalApiSource source = sourceRepo.findBySourceCode(sourceCode)
            .orElseThrow(() -> new IllegalStateException(sourceCode + " source not seeded"));

        var execLog = new com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog();
        execLog.setSource(source);
        execLog.setExecutionStatus(com.safespot.externalingestion.domain.enums.ExecutionStatus.RUNNING);
        execLog.setStartedAt(OffsetDateTime.now());
        execLog.setTraceId(traceId);
        var savedLog = executionLogRepo.save(execLog);

        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setSource(source);
        raw.setExecutionLog(savedLog);
        raw.setResponseBody(responseBody);
        raw.setPayloadHash(payloadHash);
        raw.setCollectedAt(OffsetDateTime.now());
        raw.setRetentionExpiresAt(OffsetDateTime.now().plusDays(90));
        ExternalApiRawPayload savedRaw = rawPayloadRepo.save(raw);

        normalizationService.process(
            NormalizationMessage.of(savedRaw.getRawId(), source.getSourceId(), savedLog.getExecutionId(), traceId)
        );
    }
}
