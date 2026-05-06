package com.safespot.asyncworker.service.environment;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.payload.EnvironmentDataCollectedPayload;
import com.safespot.asyncworker.redis.AirQualityCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.WeatherAlertCacheValue;
import com.safespot.asyncworker.redis.WeatherCacheValue;
import com.safespot.asyncworker.repository.AirQualityLogRecord;
import com.safespot.asyncworker.repository.EnvironmentLogRepository;
import com.safespot.asyncworker.repository.WeatherLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnvironmentCacheServiceTest {

    @Mock private EnvironmentLogRepository envLogRepository;
    @Mock private RedisCacheWriter cacheWriter;

    @InjectMocks
    private EnvironmentCacheService service;

    @Test
    void WEATHER_정상_rebuild_environment_weather_seoul_SET() {
        WeatherLogRecord record = new WeatherLogRecord(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00");
        when(envLogRepository.findLatestWeatherByTimeWindow("1h")).thenReturn(List.of(record));

        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter).setEnvironmentWeather(any(WeatherCacheValue.class));
        verify(cacheWriter, never()).setEnvironmentAirQuality(any());
    }

    @Test
    void AIR_QUALITY_정상_rebuild_environment_air_quality_seoul_SET() {
        AirQualityLogRecord record = new AirQualityLogRecord("종로구", 42, "GOOD", "2026-04-22T10:00:00");
        when(envLogRepository.findLatestAirQualityByTimeWindow("1h")).thenReturn(List.of(record));

        service.rebuild(new EnvironmentDataCollectedPayload("AIR_QUALITY", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter).setEnvironmentAirQuality(any(AirQualityCacheValue.class));
        verify(cacheWriter, never()).setEnvironmentWeather(any());
    }

    @Test
    void WEATHER_records_empty이면_SET_스킵() {
        when(envLogRepository.findLatestWeatherByTimeWindow("1h")).thenReturn(List.of());

        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter, never()).setEnvironmentWeather(any());
    }

    @Test
    void WEATHER_ALERT_rebuild_no_op_아님_weather_alert_set_호출() {
        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER_ALERT", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter).setEnvironmentWeatherAlert(any(WeatherAlertCacheValue.class));
        verify(cacheWriter, never()).setEnvironmentWeather(any());
        verify(cacheWriter, never()).setEnvironmentAirQuality(any());
    }

    @Test
    void rebuildWeatherAlertCache_no_op_아님_weather_alert_set_호출() {
        service.rebuildWeatherAlertCache();

        verify(cacheWriter).setEnvironmentWeatherAlert(any(WeatherAlertCacheValue.class));
    }

    @Test
    void weather_단일_최신_record_결정론적_SET() {
        WeatherLogRecord latest  = new WeatherLogRecord(60, 127, 22.5, "CLEAR", "2026-04-22T11:00:00");
        WeatherLogRecord earlier = new WeatherLogRecord(61, 128, 18.0, "CLOUDY", "2026-04-22T10:00:00");
        // findLatestWeatherByTimeWindow는 LIMIT 1 쿼리 — 단일 원소 리스트를 반환
        when(envLogRepository.findLatestWeatherByTimeWindow("1h")).thenReturn(List.of(latest));

        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter, times(1)).setEnvironmentWeather(any(WeatherCacheValue.class));
        // earlier record는 사용되지 않음 — 두 번 SET 되지 않는다
        verify(cacheWriter, never()).setEnvironmentAirQuality(any());
    }

    @Test
    void 미지원_collectionType_EventProcessingException() {
        assertThatThrownBy(() ->
            service.rebuild(new EnvironmentDataCollectedPayload("UNKNOWN_TYPE", "서울", "2026-04-22T10:00:00", "1h"))
        ).isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("Unsupported collectionType: UNKNOWN_TYPE");

        verifyNoInteractions(envLogRepository, cacheWriter);
    }

    @Test
    void collectionType_null_EventProcessingException() {
        assertThatThrownBy(() ->
            service.rebuild(new EnvironmentDataCollectedPayload(null, "서울", "2026-04-22T10:00:00", "1h"))
        ).isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("collectionType");
    }

    @Test
    void timeWindow_null이면_WEATHER_fallback으로_findMostRecentWeather_호출() {
        when(envLogRepository.findMostRecentWeather()).thenReturn(Optional.empty());

        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", null));

        verify(envLogRepository).findMostRecentWeather();
        verify(cacheWriter, never()).setEnvironmentWeather(any());
        verify(envLogRepository, never()).findLatestWeatherByTimeWindow(any());
    }

    @Test
    void Redis_실패시_RedisCacheException_전파() {
        when(envLogRepository.findLatestWeatherByTimeWindow("1h"))
            .thenReturn(List.of(new WeatherLogRecord(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00")));
        doThrow(new RedisCacheException("Redis SET failed"))
            .when(cacheWriter).setEnvironmentWeather(any());

        assertThatThrownBy(() ->
            service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"))
        ).isInstanceOf(RedisCacheException.class);
    }
}
