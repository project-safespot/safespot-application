package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
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

import static com.safespot.apipublicread.service.EnvironmentReadService.AIR_KEY;
import static com.safespot.apipublicread.service.EnvironmentReadService.WEATHER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnvironmentReadServiceTest {

    @Mock WeatherLogRepository weatherLogRepository;
    @Mock AirQualityLogRepository airQualityLogRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock RegionToGridResolver regionToGridResolver;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;

    @InjectMocks EnvironmentReadService environmentReadService;

    private static final int[] SEOUL_GRID = {60, 127};

    // ── findWeather: grid path ─────────────────────────────────────────────

    @Test
    void findWeather_cacheHit_returnsFromCache() {
        WeatherAlertDto cached = new WeatherAlertDto("서울", 60, 127, 18.5, "맑음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("서울", 60, 127);

        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    @Test
    void findWeather_cacheMiss_fallsBackAndPublishes() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(true);

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
        verify(cacheRegenerationPublisher).publish(WEATHER_KEY);
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findWeather_cacheMiss_suppressWindowPreventsDoublePublish() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        environmentReadService.findWeather("서울", 60, 127);

        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    @Test
    void findWeather_noData_returnsNull() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather(null, 60, 127);

        assertThat(result).isNull();
    }

    // ── findWeather: region path ───────────────────────────────────────────

    @Test
    void findWeather_regionOnly_cacheHit_returnsFromCache() {
        when(regionToGridResolver.resolve("서울특별시")).thenReturn(Optional.of(SEOUL_GRID));
        WeatherAlertDto cached = new WeatherAlertDto("서울특별시", 60, 127, 18.5, "맑음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("서울특별시", null, null);

        assertThat(result.region()).isEqualTo("서울특별시");
        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_regionOnly_cacheMiss_fallsBackAndPublishes() {
        when(regionToGridResolver.resolve("서울특별시")).thenReturn(Optional.of(SEOUL_GRID));
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(true);

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
        verify(cacheRegenerationPublisher).publish(WEATHER_KEY);
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findWeather_regionOnly_noData_returnsNull() {
        when(regionToGridResolver.resolve("서울특별시")).thenReturn(Optional.of(SEOUL_GRID));
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather("서울특별시", null, null);

        assertThat(result).isNull();
    }

    @Test
    void findWeather_regionOnly_unsupportedRegion_throwsUnsupportedRegion() {
        when(regionToGridResolver.resolve("부산광역시")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> environmentReadService.findWeather("부산광역시", null, null))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .hasToString("UNSUPPORTED_REGION");
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_allNull_throwsMissingField() {
        assertThatThrownBy(() -> environmentReadService.findWeather(null, null, null))
                .isInstanceOf(ApiException.class);
    }

    // ── findAirQuality ─────────────────────────────────────────────────────

    @Test
    void findAirQuality_cacheHit_returnsFromCache() {
        AirQualityDto cached = new AirQualityDto("종로구", 42, "좋음", "2026-04-15T15:00:00+09:00");
        when(redisReadCache.get(eq(AIR_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        AirQualityDto result = environmentReadService.findAirQuality(null, "종로구");

        assertThat(result.aqi()).isEqualTo(42);
        verify(airQualityLogRepository, never()).findLatest();
        verify(cacheRegenerationPublisher, never()).publish(any());
    }

    @Test
    void findAirQuality_cacheMiss_fallsBackAndPublishes() {
        when(redisReadCache.get(eq(AIR_KEY), any(TypeReference.class)))
                .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS));
        when(suppressWindowService.tryPublish(AIR_KEY)).thenReturn(true);

        AirQualityLog log = mock(AirQualityLog.class);
        when(log.getStationName()).thenReturn("종로구");
        when(log.getKhaiValue()).thenReturn(42);
        when(log.getKhaiGrade()).thenReturn("좋음");
        when(log.getMeasuredAt()).thenReturn(OffsetDateTime.now());
        when(airQualityLogRepository.findLatest()).thenReturn(Optional.of(log));

        AirQualityDto result = environmentReadService.findAirQuality(null, "종로구");

        assertThat(result.stationName()).isEqualTo("종로구");
        verify(redisReadCache).recordFallback(eq("/air-quality"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(AIR_KEY);
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findAirQuality_allNull_throwsMissingField() {
        assertThatThrownBy(() -> environmentReadService.findAirQuality(null, null))
                .isInstanceOf(ApiException.class);
    }
}
