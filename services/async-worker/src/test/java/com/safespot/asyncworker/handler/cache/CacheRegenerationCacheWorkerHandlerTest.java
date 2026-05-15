package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import com.safespot.asyncworker.service.shelter.ShelterMapReadModelService;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheRegenerationCacheWorkerHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;
    @Mock private ShelterMapReadModelService shelterMapReadModelService;
    @Mock private EnvironmentCacheService environmentCacheService;

    private CacheRegenerationCacheWorkerHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        handler = new CacheRegenerationCacheWorkerHandler(
            shelterStatusService, shelterMapReadModelService, environmentCacheService, objectMapper, workerMetrics);
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

    @Test
    void targetIds_batch_shelter_status_호출() {
        handler.handle(buildBatchEnvelope("SHELTER_STATUS", "shelter_status", List.of(101L, 202L)));

        verify(shelterStatusService).recalculateBatch(List.of(101L, 202L));
        verifyNoMoreInteractions(shelterStatusService);
        verifyNoInteractions(environmentCacheService);
    }

    @Test
    void targetIds_batch_단건도_처리() {
        handler.handle(buildBatchEnvelope("SHELTER_STATUS", "shelter_status", List.of(55L)));

        verify(shelterStatusService).recalculateBatch(List.of(55L));
    }

    @Test
    void targetIds_없고_cacheKey없으면_warmUpAll() {
        handler.handle(buildBatchEnvelopeWithoutIdsAndCacheKey("SHELTER_STATUS", "shelter_status"));

        verify(shelterStatusService).warmUpAll();
        verifyNoInteractions(shelterMapReadModelService, environmentCacheService);
    }

    @Test
    void targetIds_unknown_targetType_warn_no_exception() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            handler.handle(buildBatchEnvelope("UNKNOWN_TYPE", "unknown_family", List.of(1L)))
        ).isInstanceOf(EventProcessingException.class);
    }

    @Test
    void targetIds_null_targetType_skips_silently() {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", "shelter_status");
        payloadNode.put("requestedAt", "2026-05-14T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        payloadNode.set("targetIds", objectMapper.createArrayNode().add(1L));

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-batch-null-type");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-batch-null");
        envelope.setIdempotencyKey("cache-regen:batch:null:1744980300");
        envelope.setPayload(payloadNode);

        handler.handle(envelope);

        verifyNoInteractions(shelterStatusService, shelterMapReadModelService, environmentCacheService);
    }

    @Test
    void legacy_cacheKey_단건_처리() {
        handler.handle(buildEnvelope(RedisKeyConstants.shelterStatus(999L), "shelter_status"));

        verify(shelterStatusService).recalculate(999L);
        verify(shelterStatusService, never()).recalculateBatch(any());
    }

    @Test
    void legacy_null_cacheKey_null_targetIds_skips() {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", "shelter_status");
        payloadNode.put("requestedAt", "2026-05-14T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-no-key");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-no-key");
        envelope.setIdempotencyKey("cache-regen:no-key:1744980300");
        envelope.setPayload(payloadNode);

        handler.handle(envelope);

        verifyNoInteractions(shelterStatusService, shelterMapReadModelService, environmentCacheService);
    }

    @Test
    void SHELTER_MAP_ITEMS_targetType_dispatch() {
        handler.handle(buildBatchEnvelope("SHELTER_MAP_ITEMS", "shelter_map_item", List.of(101L, 202L)));

        verify(shelterMapReadModelService).rebuildMapItems(List.of(101L, 202L));
        verifyNoInteractions(shelterStatusService, environmentCacheService);
    }

    @Test
    void SHELTER_GEO_INDEX_targetType_dispatch() {
        handler.handle(buildBatchEnvelopeWithoutIds("SHELTER_GEO_INDEX", "shelter_geo"));

        verify(shelterMapReadModelService).rebuildGeoIndex();
        verifyNoInteractions(shelterStatusService, environmentCacheService);
    }

    @Test
    void SHELTER_MAP_TILES_targetType_dispatch() {
        handler.handle(buildBatchEnvelopeWithoutIds("SHELTER_MAP_TILES", "shelter_map_tile"));

        verify(shelterMapReadModelService).rebuildMapTiles();
        verifyNoInteractions(shelterStatusService, environmentCacheService);
    }

    @Test
    void SHELTER_MAP_ITEMS_targetIds_empty이면_rebuildAllMapItems() {
        handler.handle(buildBatchEnvelope("SHELTER_MAP_ITEMS", "shelter_map_item", List.of()));

        verify(shelterMapReadModelService).rebuildAllMapItems();
        verifyNoInteractions(shelterStatusService, environmentCacheService);
    }

    @Test
    void SHELTER_STATUS_targetIds_없고_cacheKey없으면_warmUpAll() {
        handler.handle(buildBatchEnvelopeWithoutIdsAndCacheKey("SHELTER_STATUS", "shelter_status"));

        verify(shelterStatusService).warmUpAll();
    }

    private EventEnvelope buildBatchEnvelope(String targetType, String cacheKeyFamily, List<Long> targetIds) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-05-14T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        payloadNode.put("targetType", targetType);
        var idsArray = objectMapper.createArrayNode();
        targetIds.forEach(idsArray::add);
        payloadNode.set("targetIds", idsArray);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-batch-001");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-batch-001");
        envelope.setIdempotencyKey("cache-regen:batch:" + targetType.toLowerCase() + ":1744980300");
        envelope.setPayload(payloadNode);
        return envelope;
    }

    private EventEnvelope buildBatchEnvelopeWithoutIds(String targetType, String cacheKeyFamily) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-05-14T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        payloadNode.put("targetType", targetType);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-batch-002");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-batch-002");
        envelope.setIdempotencyKey("cache-regen:batch:" + targetType.toLowerCase() + ":1744980301");
        envelope.setPayload(payloadNode);
        return envelope;
    }

    private EventEnvelope buildBatchEnvelopeWithoutIdsAndCacheKey(String targetType, String cacheKeyFamily) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-05-14T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        payloadNode.put("targetType", targetType);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-batch-003");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-batch-003");
        envelope.setIdempotencyKey("cache-regen:batch:" + targetType.toLowerCase() + ":1744980302");
        envelope.setPayload(payloadNode);
        return envelope;
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
