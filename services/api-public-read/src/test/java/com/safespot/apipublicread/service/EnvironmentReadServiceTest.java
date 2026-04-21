package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.AirQualityLogRepository;
import com.safespot.apipublicread.repository.WeatherLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnvironmentReadServiceTest {

    @Mock WeatherLogRepository weatherLogRepository;
    @Mock AirQualityLogRepository airQualityLogRepository;
    @Mock RedisReadCache redisReadCache;

    @InjectMocks EnvironmentReadService environmentReadService;

    @Test
    void findWeather_cacheHit_returnsFromCache() {
        WeatherAlertDto cached = new WeatherAlertDto("서울", 60, 127, 18.5, "맑음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq("env:weather:60:127"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("서울", 60, 127);

        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_cacheMiss_fallsBackAndWritesCache() {
        when(redisReadCache.get(eq("env:weather:60:127"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));

        WeatherLog log = mock(WeatherLog.class);
        when(log.getNx()).thenReturn(60);
        when(log.getNy()).thenReturn(127);
        when(log.getTmp()).thenReturn(BigDecimal.valueOf(18.5));
        when(log.getSky()).thenReturn("맑음");
        when(log.getForecastDt()).thenReturn(OffsetDateTime.now());
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.of(log));

        WeatherAlertDto result = environmentReadService.findWeather("서울", 60, 127);

        assertThat(result).isNotNull();
        assertThat(result.temperature()).isEqualTo(18.5);
        verify(redisReadCache).recordFallback(eq("/weather-alerts"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).set(eq("env:weather:60:127"), any(), any());
    }

    @Test
    void findWeather_noData_returnsNull() {
        when(redisReadCache.get(any(), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather(null, 60, 127);

        assertThat(result).isNull();
    }

    @Test
    void findWeather_regionOnly_cacheHit_returnsFromCache() {
        WeatherAlertDto cached = new WeatherAlertDto("서울특별시", 60, 127, 18.5, "맑음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq("env:weather:region:서울특별시"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("서울특별시", null, null);

        assertThat(result.region()).isEqualTo("서울특별시");
        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatest();
    }

    @Test
    void findWeather_regionOnly_cacheMiss_fallsBackAndWritesCache() {
        when(redisReadCache.get(eq("env:weather:region:서울특별시"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));

        WeatherLog log = mock(WeatherLog.class);
        when(log.getNx()).thenReturn(60);
        when(log.getNy()).thenReturn(127);
        when(log.getTmp()).thenReturn(BigDecimal.valueOf(20.0));
        when(log.getSky()).thenReturn("구름조금");
        when(log.getForecastDt()).thenReturn(OffsetDateTime.now());
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.of(log));

        WeatherAlertDto result = environmentReadService.findWeather("서울특별시", null, null);

        assertThat(result).isNotNull();
        assertThat(result.region()).isEqualTo("서울특별시");
        assertThat(result.temperature()).isEqualTo(20.0);
        verify(redisReadCache).recordFallback(eq("/weather-alerts"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(redisReadCache).set(eq("env:weather:region:서울특별시"), any(), any());
    }

    @Test
    void findWeather_regionOnly_noData_returnsNull() {
        when(redisReadCache.get(eq("env:weather:region:서울특별시"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather("서울특별시", null, null);

        assertThat(result).isNull();
    }

    @Test
    void findWeather_regionOnly_unknownRegion_returnsNull() {
        WeatherAlertDto result = environmentReadService.findWeather("알수없는지역", null, null);

        assertThat(result).isNull();
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_allNull_throwsMissingField() {
        assertThatThrownBy(() -> environmentReadService.findWeather(null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void findAirQuality_cacheHit_returnsFromCache() {
        AirQualityDto cached = new AirQualityDto("종로구", 42, "좋음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq("env:air:종로구"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        AirQualityDto result = environmentReadService.findAirQuality(null, "종로구");

        assertThat(result.aqi()).isEqualTo(42);
        verify(airQualityLogRepository, never()).findLatestByStationName(any());
    }

    @Test
    void findAirQuality_cacheMiss_fallsBackAndWritesCache() {
        when(redisReadCache.get(eq("env:air:종로구"), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));

        AirQualityLog log = mock(AirQualityLog.class);
        when(log.getStationName()).thenReturn("종로구");
        when(log.getKhaiValue()).thenReturn(42);
        when(log.getKhaiGrade()).thenReturn("좋음");
        when(log.getMeasuredAt()).thenReturn(OffsetDateTime.now());
        when(airQualityLogRepository.findLatestByStationName("종로구")).thenReturn(Optional.of(log));

        AirQualityDto result = environmentReadService.findAirQuality(null, "종로구");

        assertThat(result.stationName()).isEqualTo("종로구");
        verify(redisReadCache).set(eq("env:air:종로구"), any(), any());
    }

    @Test
    void findAirQuality_allNull_throwsMissingField() {
        assertThatThrownBy(() -> environmentReadService.findAirQuality(null, null))
                .isInstanceOf(ApiException.class);
    }
}
