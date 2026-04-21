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
        normalizer = new KmaEarthquakeNormalizer(disasterAlertRepo, detailRepo, cacheEventPublisher, metrics, om);
    }

    @Test
    void normalize_validEarthquake_savesAlertAndDetail() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"TM_FC":"202604211000","EQ_REG":"서울특별시",
               "EQ_MAG":"3.5","EQ_DPT":"10","EQ_LOC":"서울 북부 10km",
               "JDG_INTS":"진도2","WARN_VAL":"주의"}
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
            "진도2".equals(d.getIntensity())
        ));
    }

    @Test
    void normalize_duplicate_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"TM_FC":"202604211000","EQ_REG":"서울특별시",
               "EQ_MAG":"3.5","EQ_DPT":"10","EQ_LOC":"...",
               "JDG_INTS":"진도2","WARN_VAL":"주의"}
            ]}}}}
            """);

        given(disasterAlertRepo.existsBySourceAndIssuedAt(anyString(), any())).willReturn(true);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
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
