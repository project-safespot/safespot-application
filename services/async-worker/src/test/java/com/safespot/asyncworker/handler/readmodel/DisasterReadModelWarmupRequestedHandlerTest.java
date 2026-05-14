package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DisasterReadModelWarmupRequestedHandlerTest {

    @Mock private DisasterReadModelService disasterReadModelService;

    private DisasterReadModelWarmupRequestedHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new DisasterReadModelWarmupRequestedHandler(disasterReadModelService, objectMapper);
    }

    @Test
    void supportedEventType_returnsDisasterReadModelWarmupRequested() {
        assertThat(handler.supportedEventType()).isEqualTo(EventType.DisasterReadModelWarmupRequested);
    }

    @Test
    void handle_withIncludeDetails_invokesWarmupAll() {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("limit", 10);
        payloadNode.put("includeDetails", true);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-001");
        envelope.setTraceId("trace-001");
        envelope.setPayload(payloadNode);

        handler.handle(envelope);

        verify(disasterReadModelService).warmupAll(10, true);
    }

    @Test
    void handle_withoutDetails_invokesWarmupAll() {
        ObjectNode payloadNode = objectMapper.createObjectNode();
        payloadNode.put("limit", 5);
        payloadNode.put("includeDetails", false);

        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-002");
        envelope.setTraceId("trace-002");
        envelope.setPayload(payloadNode);

        handler.handle(envelope);

        verify(disasterReadModelService).warmupAll(5, false);
    }

    @Test
    void handle_invalidPayload_throwsEventProcessingException() {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-003");
        envelope.setPayload(objectMapper.createArrayNode());

        assertThatThrownBy(() -> handler.handle(envelope))
                .isInstanceOf(EventProcessingException.class)
                .hasMessageContaining("evt-003");
    }
}
