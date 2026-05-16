package com.safespot.apipublicread.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.safespot.apipublicread.cache.RedisReadCache;
import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.domain.AirQualityLog;
import com.safespot.apipublicread.domain.WeatherLog;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.dto.cache.AirQualityCacheDto;
import com.safespot.apipublicread.dto.cache.WeatherCacheDto;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.CacheRegenerationReason;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.repository.AirQualityLogRepository;
import com.safespot.apipublicread.repository.WeatherLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static com.safespot.apipublicread.service.EnvironmentReadService.AIR_KEY;
import static com.safespot.apipublicread.service.EnvironmentReadService.WEATHER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentReadServiceTest {

    @Mock WeatherLogRepository weatherLogRepository;
    @Mock AirQualityLogRepository airQualityLogRepository;
    @Mock RedisReadCache redisReadCache;
    @Mock RegionToGridResolver regionToGridResolver;
    @Mock SuppressWindowService suppressWindowService;
    @Mock CacheRegenerationPublisher cacheRegenerationPublisher;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks EnvironmentReadService environmentReadService;

    private static final int[] SEOUL_GRID = {60, 127};

    @Test
    void findWeather_cacheHit_returnsFromCache() {
        WeatherCacheDto cached = new WeatherCacheDto(60, 127, 18.5, "clear",
            null, null, null, null, OffsetDateTime.now().toString());
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("seoul", 60, 127);

        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findWeather_cacheHit_staleValue_publishesRegenerationAndReturnsCache() {
        WeatherCacheDto cached = new WeatherCacheDto(60, 127, 18.5, "clear",
            null, null, null, null, OffsetDateTime.now().minus(Duration.ofMinutes(91)).toString());
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(cached, null, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(true);

        WeatherAlertDto result = environmentReadService.findWeather("seoul", 60, 127);

        assertThat(result.temperature()).isEqualTo(18.5);
        verify(cacheRegenerationPublisher).publish(WEATHER_KEY, CacheRegenerationReason.STALE, "/weather-alerts");
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_cacheMiss_fallsBackAndPublishes() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(true);

        WeatherLog log = mock(WeatherLog.class);
        when(log.getNx()).thenReturn(60);
        when(log.getNy()).thenReturn(127);
        when(log.getTmp()).thenReturn(BigDecimal.valueOf(18.5));
        when(log.getSky()).thenReturn("clear");
        when(log.getForecastDt()).thenReturn(OffsetDateTime.now());
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.of(log));

        WeatherAlertDto result = environmentReadService.findWeather("seoul", 60, 127);

        assertThat(result).isNotNull();
        assertThat(result.temperature()).isEqualTo(18.5);
        verify(redisReadCache).recordFallback(eq("weather"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(WEATHER_KEY, CacheRegenerationReason.CACHE_MISS, "/weather-alerts");
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findWeather_cacheMiss_suppressWindowPreventsDoublePublish() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        environmentReadService.findWeather("seoul", 60, 127);

        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findWeather_parseError_fallsBackWithoutRegenerationRequest() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.PARSE_ERROR, "weather"));
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather("seoul", 60, 127);

        assertThat(result).isNull();
        verify(suppressWindowService, never()).tryPublish(anyString());
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findWeather_noData_returnsNull() {
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(anyInt(), anyInt())).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather(null, 60, 127);

        assertThat(result).isNull();
    }

    @Test
    void findWeather_regionOnly_cacheHit_returnsFromCache() {
        when(regionToGridResolver.resolve("Seoul")).thenReturn(Optional.of(SEOUL_GRID));
        WeatherCacheDto cached = new WeatherCacheDto(60, 127, 18.5, "clear",
            null, null, null, null, OffsetDateTime.now().toString());
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        WeatherAlertDto result = environmentReadService.findWeather("Seoul", null, null);

        assertThat(result.region()).isEqualTo("Seoul");
        assertThat(result.temperature()).isEqualTo(18.5);
        verify(weatherLogRepository, never()).findLatestByNxAndNy(anyInt(), anyInt());
    }

    @Test
    void findWeather_regionOnly_cacheMiss_fallsBackAndPublishes() {
        when(regionToGridResolver.resolve("Seoul")).thenReturn(Optional.of(SEOUL_GRID));
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(true);

        WeatherLog log = mock(WeatherLog.class);
        when(log.getNx()).thenReturn(60);
        when(log.getNy()).thenReturn(127);
        when(log.getTmp()).thenReturn(BigDecimal.valueOf(20.0));
        when(log.getSky()).thenReturn("cloudy");
        when(log.getForecastDt()).thenReturn(OffsetDateTime.now());
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.of(log));

        WeatherAlertDto result = environmentReadService.findWeather("Seoul", null, null);

        assertThat(result).isNotNull();
        assertThat(result.region()).isEqualTo("Seoul");
        assertThat(result.temperature()).isEqualTo(20.0);
        verify(redisReadCache).recordFallback(eq("weather"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(WEATHER_KEY, CacheRegenerationReason.CACHE_MISS, "/weather-alerts");
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findWeather_regionOnly_noData_returnsNull() {
        when(regionToGridResolver.resolve("Seoul")).thenReturn(Optional.of(SEOUL_GRID));
        when(redisReadCache.get(eq(WEATHER_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "weather"));
        when(suppressWindowService.tryPublish(WEATHER_KEY)).thenReturn(false);
        when(weatherLogRepository.findLatestByNxAndNy(60, 127)).thenReturn(Optional.empty());

        WeatherAlertDto result = environmentReadService.findWeather("Seoul", null, null);

        assertThat(result).isNull();
    }

    @Test
    void findWeather_regionOnly_unsupportedRegion_throwsUnsupportedRegion() {
        when(regionToGridResolver.resolve("Busan")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> environmentReadService.findWeather("Busan", null, null))
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

    @Test
    void findAirQuality_cacheHit_returnsFromCache() {
        AirQualityCacheDto cached = new AirQualityCacheDto("Jongno", 42, "good",
            null, null, null, null, null, null, OffsetDateTime.now().toString());
        when(redisReadCache.get(eq(AIR_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(cached, null));

        AirQualityDto result = environmentReadService.findAirQuality(null, "Jongno");

        assertThat(result.aqi()).isEqualTo(42);
        verify(airQualityLogRepository, never()).findLatest();
        verify(cacheRegenerationPublisher, never()).publish(anyString(), any(), anyString());
    }

    @Test
    void findAirQuality_cacheHit_staleValue_publishesRegenerationAndReturnsCache() {
        AirQualityCacheDto cached = new AirQualityCacheDto("Jongno", 42, "good",
            null, null, null, null, null, null, OffsetDateTime.now().minus(Duration.ofMinutes(91)).toString());
        when(redisReadCache.get(eq(AIR_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(cached, null, "air_quality"));
        when(suppressWindowService.tryPublish(AIR_KEY)).thenReturn(true);

        AirQualityDto result = environmentReadService.findAirQuality(null, "Jongno");

        assertThat(result.aqi()).isEqualTo(42);
        verify(cacheRegenerationPublisher).publish(AIR_KEY, CacheRegenerationReason.STALE, "/air-quality");
        verify(airQualityLogRepository, never()).findLatest();
    }

    @Test
    void findAirQuality_cacheMiss_fallsBackAndPublishes() {
        when(redisReadCache.get(eq(AIR_KEY), any(TypeReference.class)))
            .thenReturn(new RedisReadCache.CacheResult<>(null, RedisReadCache.FallbackReason.REDIS_MISS, "air_quality"));
        when(suppressWindowService.tryPublish(AIR_KEY)).thenReturn(true);

        AirQualityLog log = mock(AirQualityLog.class);
        when(log.getStationName()).thenReturn("Jongno");
        when(log.getKhaiValue()).thenReturn(42);
        when(log.getKhaiGrade()).thenReturn("good");
        when(log.getMeasuredAt()).thenReturn(OffsetDateTime.now());
        when(airQualityLogRepository.findLatestByStationName("Jongno")).thenReturn(Optional.of(log));

        AirQualityDto result = environmentReadService.findAirQuality(null, "Jongno");

        assertThat(result.stationName()).isEqualTo("Jongno");
        verify(airQualityLogRepository).findLatestByStationName("Jongno");
        verify(airQualityLogRepository, never()).findLatest();
        verify(redisReadCache).recordFallback(eq("air_quality"), eq(RedisReadCache.FallbackReason.REDIS_MISS));
        verify(cacheRegenerationPublisher).publish(AIR_KEY, CacheRegenerationReason.CACHE_MISS, "/air-quality");
        verify(redisReadCache, never()).set(any(), any(), any());
    }

    @Test
    void findAirQuality_allNull_throwsMissingField() {
        assertThatThrownBy(() -> environmentReadService.findAirQuality(null, null))
            .isInstanceOf(ApiException.class);
    }
}
