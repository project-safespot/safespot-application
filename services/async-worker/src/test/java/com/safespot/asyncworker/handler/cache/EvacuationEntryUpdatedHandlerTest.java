package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvacuationEntryUpdatedHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;

    private EvacuationEntryUpdatedHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new EvacuationEntryUpdatedHandler(shelterStatusService, objectMapper);
    }

    @Test
    void changedFields에_entryStatus_포함시_재계산() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId":301,"shelterId":101,"changedFields":["entryStatus","address"]}
            """);
        handler.handle(envelope);
        verify(shelterStatusService).recalculate(101L);
    }

    @Test
    void changedFields에_entryStatus_미포함시_no_op() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId":301,"shelterId":101,"changedFields":["address","familyInfo"]}
            """);
        handler.handle(envelope);
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void changedFields_빈배열시_no_op() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId":301,"shelterId":101,"changedFields":[]}
            """);
        handler.handle(envelope);
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void changedFields_null시_no_op() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"entryId":301,"shelterId":101}
            """);
        handler.handle(envelope);
        verifyNoInteractions(shelterStatusService);
    }

    private EventEnvelope buildEnvelope(String payloadJson) throws Exception {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-001");
        envelope.setEventType(EventType.EvacuationEntryUpdated.name());
        envelope.setTraceId("trace-001");
        envelope.setIdempotencyKey("entry:301:UPDATED");
        envelope.setPayload(objectMapper.readTree(payloadJson));
        return envelope;
    }
}
