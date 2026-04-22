package com.safespot.asyncworker.consumer;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.envelope.EnvelopeParser;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.idempotency.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsBatchProcessorTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private EventDispatcher eventDispatcher;

    private SqsBatchProcessor processor;

    @BeforeEach
    void setUp() {
        EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());
        processor = new SqsBatchProcessor(parser, idempotencyService, eventDispatcher);
    }

    @Test
    void 정상_처리시_failures_없음() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher).dispatch(any());
    }

    @Test
    void idempotency_중복시_no_op_성공처리() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(false);
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verifyNoInteractions(eventDispatcher);
    }

    @Test
    void Envelope_파싱_실패시_BatchItemFailure() {
        SQSEvent event = buildEvent("msg-bad", "{invalid}", 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-bad");
    }

    @Test
    void 비즈니스_예외시_BatchItemFailure() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new EventProcessingException("shelter not found"))
            .when(eventDispatcher).dispatch(any());

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
    }

    @Test
    void EvacuationEntryUpdated_수신횟수_3초과시_BatchItemFailure() {
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryUpdated"), 4);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        verifyNoInteractions(eventDispatcher);
    }

    @Test
    void idempotency_Redis_실패시_BatchItemFailure() {
        when(idempotencyService.tryAcquire(any(), any()))
            .thenThrow(new RedisCacheException("Idempotency SETNX failed", new RuntimeException()));

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-001");
        verifyNoInteractions(eventDispatcher);
    }

    @Test
    void Redis_캐시_실패시_BatchItemFailure() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new RedisCacheException("Redis SET failed", new RuntimeException()))
            .when(eventDispatcher).dispatch(any());

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-001");
    }

    @Test
    void 배치_일부_실패시_실패한_메시지만_포함() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doNothing().doThrow(new EventProcessingException("fail"))
            .when(eventDispatcher).dispatch(any());

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(
            buildMessage("msg-ok", validEnvelopeBody("EvacuationEntryCreated"), 1),
            buildMessage("msg-fail", validEnvelopeBody("ShelterUpdated"), 1)
        ));

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-fail");
    }

    private SQSEvent buildEvent(String messageId, String body, int receiveCount) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(buildMessage(messageId, body, receiveCount)));
        return event;
    }

    private SQSEvent.SQSMessage buildMessage(String messageId, String body, int receiveCount) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(body);
        message.setAttributes(Map.of("ApproximateReceiveCount", String.valueOf(receiveCount)));
        return message;
    }

    private String validEnvelopeBody(String eventType) {
        return """
            {
              "eventId": "evt-001",
              "eventType": "%s",
              "traceId": "trace-001",
              "idempotencyKey": "key-001",
              "payload": {"entryId": 1, "shelterId": 101, "shelterId": 101, "changedFields": []}
            }
            """.formatted(eventType);
    }
}
