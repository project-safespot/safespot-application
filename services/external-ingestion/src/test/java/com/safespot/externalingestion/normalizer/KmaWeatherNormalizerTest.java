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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// Tests use getUltraSrtNcst (초단기실황) response shape: obsrValue, no fcstDate/fcstTime
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
              {"baseDate":"20260421","baseTime":"1000","category":"T1H",
               "obsrValue":"15","nx":60,"ny":127},
              {"baseDate":"20260421","baseTime":"1000","category":"REH",
               "obsrValue":"60","nx":60,"ny":127},
              {"baseDate":"20260421","baseTime":"1000","category":"WSD",
               "obsrValue":"3.5","nx":60,"ny":127}
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
        assertThat(saved.getBaseTime()).isEqualTo("1000");
        assertThat(saved.getTmp()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(saved.getReh()).isEqualTo(60);
        assertThat(saved.getWsd()).isEqualByComparingTo(new BigDecimal("3.5"));

        verify(cacheEventPublisher).publish(any(), any());
    }

    @Test
    void normalize_ptyCategory_mapsToKorean() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"baseDate":"20260421","baseTime":"1000","category":"PTY",
               "obsrValue":"1","nx":60,"ny":127}
            ]}}}}
            """);

        given(weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(any(), any(), any(), any(), any()))
            .willReturn(false);

        normalizer.normalize(raw);

        ArgumentCaptor<WeatherLog> captor = ArgumentCaptor.forClass(WeatherLog.class);
        verify(weatherLogRepo).save(captor.capture());
        assertThat(captor.getValue().getPty()).isEqualTo("비");
    }

    @Test
    void normalize_duplicateForecast_skipsInsert() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"baseDate":"20260421","baseTime":"1000","category":"T1H",
               "obsrValue":"15","nx":60,"ny":127}
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

    @Test
    void normalize_forecastDtEqualsBaseDateTime() {
        ExternalApiRawPayload raw = buildRaw("""
            {"response":{"body":{"items":{"item":[
              {"baseDate":"20260421","baseTime":"1000","category":"T1H",
               "obsrValue":"20","nx":60,"ny":127}
            ]}}}}
            """);

        given(weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(any(), any(), any(), any(), any()))
            .willReturn(false);

        normalizer.normalize(raw);

        ArgumentCaptor<WeatherLog> captor = ArgumentCaptor.forClass(WeatherLog.class);
        verify(weatherLogRepo).save(captor.capture());
        WeatherLog saved = captor.getValue();
        // forecast_dt = base_date + base_time (same as observation time for 초단기실황)
        assertThat(saved.getForecastDt().getHour()).isEqualTo(10);
        assertThat(saved.getBaseTime()).isEqualTo("1000");
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
