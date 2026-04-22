package com.safespot.asyncworker.service.environment;

import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.payload.EnvironmentDataCollectedPayload;
import com.safespot.asyncworker.redis.AirQualityCacheValue;
import com.safespot.asyncworker.redis.RedisCacheWriter;
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
    void WEATHER_정상_rebuild() {
        WeatherLogRecord record = new WeatherLogRecord(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00");
        when(envLogRepository.findLatestWeatherByTimeWindow("1h")).thenReturn(List.of(record));

        service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter).setWeather(any(WeatherCacheValue.class));
        verify(cacheWriter, never()).setAirQuality(any());
    }

    @Test
    void AIR_QUALITY_정상_rebuild() {
        AirQualityLogRecord record = new AirQualityLogRecord("종로구", 42, "GOOD", "2026-04-22T10:00:00");
        when(envLogRepository.findLatestAirQualityByTimeWindow("1h")).thenReturn(List.of(record));

        service.rebuild(new EnvironmentDataCollectedPayload("AIR_QUALITY", "서울", "2026-04-22T10:00:00", "1h"));

        verify(cacheWriter).setAirQuality(any(AirQualityCacheValue.class));
        verify(cacheWriter, never()).setWeather(any());
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
    void timeWindow_null_EventProcessingException() {
        assertThatThrownBy(() ->
            service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", null))
        ).isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("timeWindow");
    }

    @Test
    void Redis_실패시_RedisCacheException_전파() {
        when(envLogRepository.findLatestWeatherByTimeWindow("1h"))
            .thenReturn(List.of(new WeatherLogRecord(60, 127, 22.5, "CLEAR", "2026-04-22T10:00:00")));
        doThrow(new RedisCacheException("Redis SET failed"))
            .when(cacheWriter).setWeather(any());

        assertThatThrownBy(() ->
            service.rebuild(new EnvironmentDataCollectedPayload("WEATHER", "서울", "2026-04-22T10:00:00", "1h"))
        ).isInstanceOf(RedisCacheException.class);
    }
}
