package com.safespot.externalingestion.integration;

import com.safespot.externalingestion.config.DataInitializer;
import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.queue.NormalizationMessage;
import com.safespot.externalingestion.queue.NormalizationQueue;
import com.safespot.externalingestion.repository.*;
import com.safespot.externalingestion.service.NormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 인메모리 DB로 수집 → 정규화 흐름 통합 검증
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
    @Autowired DataInitializer dataInitializer;

    @Test
    void dataInitializer_seedsAllSources() {
        List<ExternalApiSource> sources = sourceRepo.findAll();
        assertThat(sources).hasSizeGreaterThanOrEqualTo(10);

        assertThat(sources).anyMatch(s -> "SAFETY_DATA_ALERT".equals(s.getSourceCode()) && s.isActive());
        assertThat(sources).anyMatch(s -> "FORESTRY_LANDSLIDE".equals(s.getSourceCode()) && !s.isActive());
        assertThat(sources).anyMatch(s -> "KMA_WEATHER".equals(s.getSourceCode()));
    }

    @Test
    void normalizationService_processesDisasterAlert() {
        ExternalApiSource source = sourceRepo.findBySourceCode("SAFETY_DATA_ALERT")
            .orElseThrow(() -> new IllegalStateException("SAFETY_DATA_ALERT source not seeded"));

        var execLog = new com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog();
        execLog.setSource(source);
        execLog.setExecutionStatus(com.safespot.externalingestion.domain.enums.ExecutionStatus.RUNNING);
        execLog.setStartedAt(OffsetDateTime.now());
        execLog.setTraceId("integration-test-trace");
        var savedLog = executionLogRepo.save(execLog);

        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setSource(source);
        raw.setExecutionLog(savedLog);
        raw.setResponseBody("""
            {"response":{"body":{"items":{"item":[
              {"MSG_CN":"통합테스트 홍수 경보","RCPTN_RGN_NM":"서울특별시",
               "EMRG_STEP_NM":"경계","DST_SE_NM":"홍수","CRT_DT":"2026-04-21 15:00:00"}
            ]}}}}
            """);
        raw.setPayloadHash("integration-test-hash-001");
        raw.setCollectedAt(OffsetDateTime.now());
        raw.setRetentionExpiresAt(OffsetDateTime.now().plusDays(90));
        ExternalApiRawPayload savedRaw = rawPayloadRepo.save(raw);

        NormalizationMessage msg = NormalizationMessage.of(
            savedRaw.getRawId(), source.getSourceId(), savedLog.getExecutionId(), "integration-test-trace");

        normalizationService.process(msg);

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
        assertThat(count).isEqualTo(1); // 중복 SKIP으로 1건만 저장
    }
}
