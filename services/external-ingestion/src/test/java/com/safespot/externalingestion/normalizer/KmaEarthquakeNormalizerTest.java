package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.repository.DisasterAlertDetailRepository;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KmaEarthquakeNormalizerTest {

    @Mock private DisasterAlertRepository disasterAlertRepo;
    @Mock private DisasterAlertDetailRepository detailRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private KmaEarthquakeNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new KmaEarthquakeNormalizer(disasterAlertRepo, detailRepo, cacheEventPublisher, metrics, om, new SeoulScopePolicy());
    }

    @Test
    void normalize_validEarthquake_savesAlertAndDetail() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"tmFc":"202604211000","tmEqk":"20260421095800",
               "lat":"37.56","lon":"126.97","loc":"서울 북부 10km",
               "mt":"3.5","inT":"최대진도Ⅱ"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(1L);
        saved.setRegion("서울특별시");
        saved.setDisasterType("EARTHQUAKE");
        given(disasterAlertRepo.save(any())).willReturn(saved);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);

        verify(disasterAlertRepo).save(argThat(a -> "EARTHQUAKE".equals(a.getDisasterType())));
        verify(detailRepo).save(argThat(d ->
            "EARTHQUAKE".equals(d.getDetailType()) &&
            new BigDecimal("3.5").compareTo(d.getMagnitude()) == 0 &&
            "최대진도Ⅱ".equals(d.getIntensity())
        ));
    }

    @Test
    void normalize_duplicate_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"tmFc":"202604211000","tmEqk":"20260421095800",
               "lat":"37.56","lon":"126.97","loc":"서울 북부 10km",
               "mt":"3.5","inT":"최대진도Ⅱ"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any())).willReturn(true);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
    }

    @Test
    void normalize_detailFails_compensatesAlertDelete() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"tmFc":"202604221100","tmEqk":"20260422105500",
               "lat":"37.50","lon":"127.00","loc":"서울 남부 5km",
               "mt":"4.1","inT":"최대진도Ⅴ"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(99L);
        saved.setRegion("서울특별시");
        saved.setDisasterType("EARTHQUAKE");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);
        given(detailRepo.save(any())).willThrow(new RuntimeException("db constraint violation"));

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(1);
        // alert must be compensated (deleted) so dedup does not block retry
        verify(disasterAlertRepo).delete(saved);
        // cache event must NOT fire
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_nonSeoulRegion_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"tmFc":"202604211000","tmEqk":"20260421095800",
               "lat":"35.17","lon":"129.07","loc":"부산 북부 10km",
               "mt":"3.5","inT":"최대진도Ⅱ"}
            ]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(disasterAlertRepo, never()).save(any());
        verify(cacheEventPublisher, never()).publish(any(), any());
    }

    @Test
    void normalize_seoulAlert_usesCanonicalRegion() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"tmFc":"202604211100","tmEqk":"20260421105800",
               "lat":"37.56","lon":"126.98","loc":"서울 중구",
               "mt":"2.1","inT":"최대진도Ⅰ"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any(OffsetDateTime.class))).willReturn(false);
        DisasterAlert saved = new DisasterAlert();
        saved.setAlertId(5L);
        saved.setDisasterType("EARTHQUAKE");
        given(disasterAlertRepo.save(any(DisasterAlert.class))).willReturn(saved);

        normalizer.normalize(raw);

        verify(disasterAlertRepo).save(argThat(a ->
            "seoul".equals(a.getRegion()) &&
            "서울 중구".equals(a.getSourceRegion())
        ));
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
