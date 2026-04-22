package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class EvacuationEntryExitedHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;

    private EvacuationEntryExitedHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new EvacuationEntryExitedHandler(shelterStatusService, objectMapper);
    }

    @Test
    void 정상_payload_shelterStatus_재계산() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId": 2, "shelterId": 102, "nextStatus": "EXITED",
             "recordedByAdminId": 9, "exitedAt": "2026-04-22T11:00:00"}
            """);

        handler.handle(envelope);

        verify(shelterStatusService).recalculate(eq(102L));
    }

    @Test
    void invalid_payload_EventProcessingException() throws Exception {
        EventEnvelope envelope = buildEnvelope("[1, 2, 3]");

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(EventProcessingException.class)
            .hasMessageContaining("EvacuationEntryExited");
    }

    @Test
    void Redis_실패시_RedisCacheException_전파() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId": 2, "shelterId": 102, "nextStatus": "EXITED",
             "recordedByAdminId": 9, "exitedAt": "2026-04-22T11:00:00"}
            """);
        doThrow(new RedisCacheException("Redis SET failed"))
            .when(shelterStatusService).recalculate(102L);

        assertThatThrownBy(() -> handler.handle(envelope))
            .isInstanceOf(RedisCacheException.class);
    }

    private EventEnvelope buildEnvelope(String payloadJson) throws Exception {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-002");
        envelope.setEventType("EvacuationEntryExited");
        envelope.setTraceId("trace-002");
        envelope.setIdempotencyKey("key-002");
        envelope.setPayload(objectMapper.readTree(payloadJson));
        return envelope;
    }
}
