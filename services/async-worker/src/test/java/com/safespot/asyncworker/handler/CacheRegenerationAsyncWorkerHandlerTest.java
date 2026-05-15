package com.safespot.asyncworker.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheRegenerationAsyncWorkerHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;
    @Mock private ShelterMapReadModelService shelterMapReadModelService;
    @Mock private EnvironmentCacheService environmentCacheService;
    @Mock private DisasterReadModelService disasterReadModelService;

    private CacheRegenerationAsyncWorkerHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        handler = new CacheRegenerationAsyncWorkerHandler(
            shelterStatusService,
            shelterMapReadModelService,
            environmentCacheService,
            disasterReadModelService,
            objectMapper,
            workerMetrics
        );
    }

    @Test
    void SHELTER_MAP_ITEMS_targetType_dispatch() {
        handler.handle(buildTargetEnvelope("SHELTER_MAP_ITEMS", "shelter_map_item", null, List.of(101L, 202L)));

        verify(shelterMapReadModelService).rebuildMapItems(List.of(101L, 202L));
        verifyNoInteractions(shelterStatusService, environmentCacheService, disasterReadModelService);
    }

    @Test
    void SHELTER_GEO_INDEX_targetType_dispatch() {
        handler.handle(buildTargetEnvelope("SHELTER_GEO_INDEX", "shelter_geo", null, null));

        verify(shelterMapReadModelService).rebuildGeoIndex();
        verifyNoInteractions(shelterStatusService, environmentCacheService, disasterReadModelService);
    }

    @Test
    void SHELTER_MAP_TILES_targetType_dispatch() {
        handler.handle(buildTargetEnvelope("SHELTER_MAP_TILES", "shelter_map_tile", null, null));

        verify(shelterMapReadModelService).rebuildMapTiles();
        verifyNoInteractions(shelterStatusService, environmentCacheService, disasterReadModelService);
    }

    @Test
    void SHELTER_MAP_ITEMS_targetIds_empty이면_EventProcessingException() {
        assertThatThrownBy(() ->
            handler.handle(buildTargetEnvelope("SHELTER_MAP_ITEMS", "shelter_map_item", null, List.of()))
        ).isInstanceOf(EventProcessingException.class);
    }

    @Test
    void unsupported_targetType이면_EventProcessingException() {
        assertThatThrownBy(() ->
            handler.handle(buildTargetEnvelope("UNKNOWN_TYPE", "unknown", null, List.of(1L)))
        ).isInstanceOf(EventProcessingException.class);
    }

    @Test
    void targetType_없으면_legacy_shelter_status_path_유지() {
        handler.handle(buildLegacyEnvelope(RedisKeyConstants.shelterStatus(101L), "shelter_status"));

        verify(shelterStatusService).recalculate(101L);
        verifyNoInteractions(shelterMapReadModelService, environmentCacheService, disasterReadModelService);
    }

    @Test
    void targetType_없으면_legacy_environment_path_유지() {
        handler.handle(buildLegacyEnvelope(RedisKeyConstants.ENVIRONMENT_WEATHER, "environment_weather"));

        verify(environmentCacheService).rebuildWeatherCache();
        verifyNoInteractions(shelterStatusService, shelterMapReadModelService, disasterReadModelService);
    }

    @Test
    void targetType_없으면_legacy_disaster_path_유지() {
        handler.handle(buildLegacyEnvelope(RedisKeyConstants.DISASTER_MESSAGES_LIST, "disaster_messages_list"));

        verify(disasterReadModelService).rebuildList();
        verifyNoInteractions(shelterStatusService, shelterMapReadModelService, environmentCacheService);
    }

    private EventEnvelope buildTargetEnvelope(String targetType, String cacheKeyFamily, String cacheKey, List<Long> targetIds) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-05-15T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        payloadNode.put("targetType", targetType);
        if (cacheKey != null) {
            payloadNode.put("cacheKey", cacheKey);
        }
        if (targetIds != null) {
            var ids = objectMapper.createArrayNode();
            targetIds.forEach(ids::add);
            payloadNode.set("targetIds", ids);
        }
        return buildEnvelope(payloadNode);
    }

    private EventEnvelope buildLegacyEnvelope(String cacheKey, String cacheKeyFamily) {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("cacheKey", cacheKey);
        payloadNode.put("cacheKeyFamily", cacheKeyFamily);
        payloadNode.put("requestedAt", "2026-05-15T10:00:00Z");
        payloadNode.put("reason", "cache_miss");
        payloadNode.put("schemaVersion", 1);
        return buildEnvelope(payloadNode);
    }

    private EventEnvelope buildEnvelope(ObjectNode payloadNode) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-regen-001");
        envelope.setEventType(EventType.CacheRegenerationRequested.name());
        envelope.setTraceId("trace-001");
        envelope.setIdempotencyKey("cache-regen:test:1744980300");
        envelope.setPayload(payloadNode);
        return envelope;
    }
}
