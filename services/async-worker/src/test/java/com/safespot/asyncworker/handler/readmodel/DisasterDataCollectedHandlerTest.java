package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisasterDataCollectedHandlerTest {

    @Mock private DisasterReadModelService disasterReadModelService;

    private DisasterDataCollectedHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new DisasterDataCollectedHandler(disasterReadModelService, objectMapper);
    }

    @Test
    void 정상_payload_처리() {
        EventEnvelope envelope = buildEnvelope(validPayload());

        handler.handle(envelope);

        verify(disasterReadModelService).rebuild(any());
    }

    @Test
    void invalid_payload_EventProcessingException() {
        // ObjectNode 타입이 아닌 ArrayNode를 전달하면 Jackson 역직렬화 실패
        EventEnvelope envelope = buildEnvelope(objectMapper.createArrayNode());

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(EventProcessingException.class);
    }

    @Test
    void Redis_실패시_예외_전파() {
        EventEnvelope envelope = buildEnvelope(validPayload());
        doThrow(new RedisCacheException("Redis SET failed", new RuntimeException()))
            .when(disasterReadModelService).rebuild(any());

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(RedisCacheException.class);
    }

    private EventEnvelope buildEnvelope(com.fasterxml.jackson.databind.JsonNode payload) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-001");
        envelope.setEventType(EventType.DisasterDataCollected.name());
        envelope.setTraceId("trace-001");
        envelope.setIdempotencyKey("key-001");
        envelope.setPayload(payload);
        return envelope;
    }

    private com.fasterxml.jackson.databind.JsonNode validPayload() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("collectionType", "FLOOD");
        node.put("region", "서울");
        node.put("hasExpiredAlerts", false);
        node.put("completedAt", "2026-04-22T10:00:00");
        node.putArray("affectedAlertIds").add(1L);
        return node;
    }
}
