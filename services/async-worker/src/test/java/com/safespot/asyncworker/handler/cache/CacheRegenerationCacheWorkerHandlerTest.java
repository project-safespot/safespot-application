package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheRegenerationCacheWorkerHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;
    @Mock private EnvironmentCacheService environmentCacheService;

    private CacheRegenerationCacheWorkerHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        handler = new CacheRegenerationCacheWorkerHandler(
            shelterStatusService, environmentCacheService, objectMapper, workerMetrics);
    }

    @Test
    void shelter_status_key_shelterId_추출_후_재계산() {
        handler.handle(buildEnvelope(RedisKeyConstants.shelterStatus(101L), "shelter_status"));

        verify(shelterStatusService).recalculate(101L);
        verifyNoInteractions(environmentCacheService);
    }

    @Test
    void environment_weather_key_weather_cache_rebuild() {
        handler.handle(buildEnvelope(RedisKeyConstants.ENVIRONMENT_WEATHER, "environment_weather"));

        verify(environmentCacheService).rebuildWeatherCache();
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void environment_air_quality_key_air_quality_cache_rebuild() {
        handler.handle(buildEnvelope(RedisKeyConstants.ENVIRONMENT_AIR_QUALITY, "environment_air_quality"));

        verify(environmentCacheService).rebuildAirQualityCache();
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void environment_weather_alert_key_weather_alert_cache_rebuild() {
        handler.handle(buildEnvelope(RedisKeyConstants.ENVIRONMENT_WEATHER_ALERT, "environment_weather_alert"));

        verify(environmentCacheService).rebuildWeatherAlertCache();
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void unhandled_cacheKey_no_op_no_exception() {
        handler.handle(buildEnvelope("unknown:key:123", "unknown_family"));

        verifyNoInteractions(shelterStatusService, environmentCacheService);
    }

    @Test
    void invalid_shelterId_format_EventProcessingException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            handler.handle(buildEnvelope("shelter:status:not-a-number", "shelter_status"))
        ).isInstanceOf(EventProcessingException.class);
    }

    private EventEnvelope buildEnvelope(String cacheKey, String cacheKeyFamily) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKey", cacheKey);
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-04-15T15:05:00+09:00");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-regen-001");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-001");
        envelope.setIdempotencyKey("cache-regen:test:1744980300");
        envelope.setPayload(payloadNode);
        return envelope;
    }
}
