package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.domain.entity.WeatherLog;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.repository.WeatherLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KmaWeatherNormalizerTest {

    @Mock private WeatherLogRepository weatherLogRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private KmaWeatherNormalizer normalizer;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        normalizer = new KmaWeatherNormalizer(weatherLogRepo, cacheEventPublisher, metrics, om);
    }

    @Test
    void normalize_multipleCategories_mergedAndSaved() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"baseDate":"20260421","baseTime":"0500","category":"TMP",
               "fcstDate":"20260421","fcstTime":"0600","fcstValue":"15","nx":60,"ny":127},
              {"baseDate":"20260421","baseTime":"0500","category":"SKY",
               "fcstDate":"20260421","fcstTime":"0600","fcstValue":"1","nx":60,"ny":127},
              {"baseDate":"20260421","baseTime":"0500","category":"POP",
               "fcstDate":"20260421","fcstTime":"0600","fcstValue":"20","nx":60,"ny":127}
            ]}}}}
            """);

        given(weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(any(), any(), any(), any(), any()))
            .willReturn(false);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(0);

        ArgumentCaptor<WeatherLog> captor = ArgumentCaptor.forClass(WeatherLog.class);
        verify(weatherLogRepo).save(captor.capture());
        WeatherLog saved = captor.getValue();
        assertThat(saved.getNx()).isEqualTo(60);
        assertThat(saved.getNy()).isEqualTo(127);
        assertThat(saved.getSky()).isEqualTo("맑음");
        assertThat(saved.getPop()).isEqualTo(20);

        verify(cacheEventPublisher).publish(any(), any());
    }

    @Test
    void normalize_duplicateForecast_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"baseDate":"20260421","baseTime":"0500","category":"TMP",
               "fcstDate":"20260421","fcstTime":"0600","fcstValue":"15","nx":60,"ny":127}
            ]}}}}
            """);

        given(weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(any(), any(), any(), any(), any()))
            .willReturn(true);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(weatherLogRepo, never()).save(any());
    }

    @Test
    void normalize_emptyItems_returnsZero() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[]}}}}
            """);

        NormalizationResult result = normalizer.normalize(raw);

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isEqualTo(0);
    }

    private ExternalApiRawPayload buildRaw(String body) {
        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setTraceId("trace-weather");
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode("KMA_WEATHER");
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
