package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheRegenerationReadModelWorkerHandlerTest {

    @Mock private DisasterReadModelService disasterReadModelService;

    private CacheRegenerationReadModelWorkerHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        handler = new CacheRegenerationReadModelWorkerHandler(
            disasterReadModelService, objectMapper, workerMetrics);
    }

    @Test
    void disaster_messages_recent_key_rebuildRecent_호출() {
        handler.handle(buildEnvelope(RedisKeyConstants.DISASTER_MESSAGES_RECENT, "disaster_messages_recent"));

        verify(disasterReadModelService).rebuildRecent();
        verifyNoMoreInteractions(disasterReadModelService);
    }

    @Test
    void disaster_message_core_key_rebuildCore_호출() {
        handler.handle(buildEnvelope(RedisKeyConstants.DISASTER_MESSAGE_CORE, "disaster_message_core"));

        verify(disasterReadModelService).rebuildCore();
        verifyNoMoreInteractions(disasterReadModelService);
    }

    @Test
    void disaster_messages_list_key_rebuildList_호출() {
        handler.handle(buildEnvelope(RedisKeyConstants.DISASTER_MESSAGES_LIST, "disaster_messages_list"));

        verify(disasterReadModelService).rebuildList();
        verifyNoMoreInteractions(disasterReadModelService);
    }

    @Test
    void disaster_detail_key_alertId_추출_후_rebuildDetail_호출() {
        handler.handle(buildEnvelope("disaster:detail:42", "disaster_detail"));

        verify(disasterReadModelService).rebuildDetail(42L);
        verifyNoMoreInteractions(disasterReadModelService);
    }

    @Test
    void disaster_detail_invalid_alertId_EventProcessingException() {
        assertThatThrownBy(() ->
            handler.handle(buildEnvelope("disaster:detail:not-a-number", "disaster_detail"))
        ).isInstanceOf(EventProcessingException.class);
    }

    @Test
    void unhandled_cacheKey_no_op_no_exception() {
        handler.handle(buildEnvelope("unknown:key", "unknown_family"));

        verifyNoInteractions(disasterReadModelService);
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
