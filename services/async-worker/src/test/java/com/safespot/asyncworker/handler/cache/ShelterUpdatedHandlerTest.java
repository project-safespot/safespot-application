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
class ShelterUpdatedHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;

    private ShelterUpdatedHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new ShelterUpdatedHandler(shelterStatusService, objectMapper);
    }

    @Test
    void changedFields에_capacityTotal_포함시_재계산() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"shelterId":101,"changedFields":["capacityTotal","note"]}
            """);
        handler.handle(envelope);
        verify(shelterStatusService).recalculate(101L);
    }

    @Test
    void changedFields에_shelterStatus_포함시_재계산() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"shelterId":101,"changedFields":["shelterStatus"]}
            """);
        handler.handle(envelope);
        verify(shelterStatusService).recalculate(101L);
    }

    @Test
    void changedFields에_트리거_필드_미포함시_no_op() throws Exception {
        EventEnvelope envelope = buildEnvelope("""
            {"shelterId":101,"changedFields":["note","manager"]}
            """);
        handler.handle(envelope);
        verifyNoInteractions(shelterStatusService);
    }

    @Test
    void Redis_비교_없이_항상_RDS_재계산() throws Exception {
        // changedFields 조건 통과 후 Redis 이전 상태를 조회하지 않음을 확인
        // shelterStatusService.recalculate 호출이 1회만 발생해야 함
        EventEnvelope envelope = buildEnvelope("""
            {"shelterId":101,"changedFields":["capacityTotal"]}
            """);
        handler.handle(envelope);
        verify(shelterStatusService, times(1)).recalculate(101L);
        verifyNoMoreInteractions(shelterStatusService);
    }

    private EventEnvelope buildEnvelope(String payloadJson) throws Exception {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId("evt-002");
        envelope.setEventType(EventType.ShelterUpdated.name());
        envelope.setTraceId("trace-002");
        envelope.setIdempotencyKey("shelter:101:UPDATED");
        envelope.setPayload(objectMapper.readTree(payloadJson));
        return envelope;
    }
}
