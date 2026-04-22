package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvacuationEntryCreatedHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;

    private EvacuationEntryCreatedHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new EvacuationEntryCreatedHandler(shelterStatusService, objectMapper);
    }

    @Test
    void 정상_payload_shelterStatus_재계산() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId": 1, "shelterId": 101, "nextStatus": "ENTERED",
             "recordedByAdminId": 9, "enteredAt": "2026-04-22T10:00:00"}
            """);

        handler.handle(envelope);

        verify(shelterStatusService).recalculate(eq(101L));
    }

    @Test
    void invalid_payload_EventProcessingException() throws Exception {
        EventEnvelope envelope = buildEnvelope("[1, 2, 3]");

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("EvacuationEntryCreated");
    }

    @Test
    void Redis_실패시_RedisCacheException_전파() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId": 1, "shelterId": 101, "nextStatus": "ENTERED",
             "recordedByAdminId": 9, "enteredAt": "2026-04-22T10:00:00"}
            """);
        doThrow(new RedisCacheException("Redis SET failed"))
            .when(shelterStatusService).recalculate(101L);

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(RedisCacheException.class);
    }

    private EventEnvelope buildEnvelope(String payloadJson) throws Exception {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-001");
        envelope.setEventType("EvacuationEntryCreated");
        envelope.setTraceId("trace-001");
        envelope.setIdempotencyKey("key-001");
        envelope.setPayload(objectMapper.readTree(payloadJson));
        return envelope;
    }
}
