package com.safespot.asyncworker.consumer;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.envelope.EnvelopeParser;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.exception.RedisCacheException;
import com.safespot.asyncworker.idempotency.IdempotencyService;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsBatchProcessorTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private EventDispatcher eventDispatcher;

    private SqsBatchProcessor processor;

    @BeforeEach
    void setUp() {
        EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());
        WorkerMetrics workerMetrics = new WorkerMetrics(new SimpleMeterRegistry());
        processor = new SqsBatchProcessor(parser, idempotencyService, eventDispatcher, workerMetrics);
    }

    // ── 정상 처리 ──────────────────────────────────────────────────────────────

    @Test
    void 정상_처리시_failures_없음() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher).dispatch(any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void idempotency_중복시_no_op_성공처리() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(false);
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verifyNoInteractions(eventDispatcher);
        verify(idempotencyService, never()).release(any());
    }

    // ── 파싱 실패 ─────────────────────────────────────────────────────────────

    @Test
    void Envelope_파싱_실패시_BatchItemFailure() {
        SQSEvent event = buildEvent("msg-bad", "{invalid}", 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-bad");
        verifyNoInteractions(idempotencyService);
    }

    // ── retry 안전성 ───────────────────────────────────────────────────────────

    @Test
    void 비즈니스_예외시_release_후_BatchItemFailure() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new EventProcessingException("shelter not found"))
            .when(eventDispatcher).dispatch(any());

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        verify(idempotencyService).release(eq("key-001"));
    }

    @Test
    void Redis_캐시_실패시_release_후_BatchItemFailure() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new RedisCacheException("Redis SET failed", new RuntimeException()))
            .when(eventDispatcher).dispatch(any());

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-001");
        verify(idempotencyService).release(eq("key-001"));
    }

    @Test
    void 실패_후_재시도시_정상_처리됨() {
        // 1차 시도: dispatch 실패 → release 호출
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new RedisCacheException("Redis SET failed", new RuntimeException()))
            .when(eventDispatcher).dispatch(any());

        SQSEvent firstAttempt = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse firstResponse = processor.process(firstAttempt);
        assertThat(firstResponse.getBatchItemFailures()).hasSize(1);
        verify(idempotencyService).release(eq("key-001"));

        // 2차 시도: release 이후 재시도 — tryAcquire=true (키 삭제됨), dispatch 성공
        reset(idempotencyService, eventDispatcher);
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);

        SQSEvent retryAttempt = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 2);
        SQSBatchResponse retryResponse = processor.process(retryAttempt);

        assertThat(retryResponse.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher).dispatch(any());
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void release_자체_실패시_원래_BatchItemFailure_유지() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        doThrow(new EventProcessingException("dispatch failed")).when(eventDispatcher).dispatch(any());
        doThrow(new RedisCacheException("DEL failed")).when(idempotencyService).release(any());

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        // release 실패해도 원래 실패 결과 반환 (예외 전파 안 됨)
        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-001");
    }

    // ── idempotency 자체 실패 ─────────────────────────────────────────────────

    @Test
    void idempotency_Redis_실패시_release_없이_BatchItemFailure() {
        // tryAcquire 자체가 실패한 경우 — acquired=false이므로 release 불필요
        when(idempotencyService.tryAcquire(any(), any()))
            .thenThrow(new RedisCacheException("Idempotency SETNX failed", new RuntimeException()));

        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryCreated"), 1);
        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-001");
        verifyNoInteractions(eventDispatcher);
        verify(idempotencyService, never()).release(any());
    }

    // ── 앱 재시도 한도 (5회 통일) ──────────────────────────────────────────────

    @Test
    void 수신횟수_5초과시_BatchItemFailure() {
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryUpdated"), 6);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        verifyNoInteractions(eventDispatcher);
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void 수신횟수_5이하시_정상_처리() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        SQSEvent event = buildEvent("msg-001", validEnvelopeBody("EvacuationEntryUpdated"), 5);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher).dispatch(any());
    }

    // ── 배치 부분 실패 ────────────────────────────────────────────────────────

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
        verify(idempotencyService, times(1)).release(any());
    }

    // ── update 이벤트 idempotencyKey 검증 ────────────────────────────────────

    @Test
    void 동일_eventId_재전달시_dedupe() {
        // 같은 idempotencyKey → 두 번째는 tryAcquire=false → no-op
        when(idempotencyService.tryAcquire(any(), any()))
            .thenReturn(true)
            .thenReturn(false);

        String key = "entry:301:UPDATED:evt-abc";
        SQSEvent first  = buildEvent("msg-1", envelopeBody("EvacuationEntryUpdated", key, "evt-abc"), 1);
        SQSEvent second = buildEvent("msg-2", envelopeBody("EvacuationEntryUpdated", key, "evt-abc"), 1);

        processor.process(first);
        SQSBatchResponse response = processor.process(second);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher, times(1)).dispatch(any()); // 첫 번째만 실제 처리
        verify(idempotencyService, never()).release(any());
    }

    @Test
    void 같은_entryId_다른_eventId_update_두건_모두_처리() {
        // 서로 다른 eventId → 서로 다른 idempotencyKey → 둘 다 tryAcquire=true
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);

        SQSEvent first  = buildEvent("msg-1", envelopeBody("EvacuationEntryUpdated",
            "entry:301:UPDATED:evt-aaa", "evt-aaa"), 1);
        SQSEvent second = buildEvent("msg-2", envelopeBody("EvacuationEntryUpdated",
            "entry:301:UPDATED:evt-bbb", "evt-bbb"), 1);

        SQSBatchResponse r1 = processor.process(first);
        SQSBatchResponse r2 = processor.process(second);

        assertThat(r1.getBatchItemFailures()).isEmpty();
        assertThat(r2.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher, times(2)).dispatch(any()); // 두 건 모두 처리
    }

    @Test
    void 같은_shelterId_다른_eventId_update_두건_모두_처리() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);

        SQSEvent first  = buildEvent("msg-1", envelopeBody("ShelterUpdated",
            "shelter:101:UPDATED:evt-aaa", "evt-aaa"), 1);
        SQSEvent second = buildEvent("msg-2", envelopeBody("ShelterUpdated",
            "shelter:101:UPDATED:evt-bbb", "evt-bbb"), 1);

        SQSBatchResponse r1 = processor.process(first);
        SQSBatchResponse r2 = processor.process(second);

        assertThat(r1.getBatchItemFailures()).isEmpty();
        assertThat(r2.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher, times(2)).dispatch(any());
    }

    // ── CacheRegenerationRequested 처리 ───────────────────────────────────────

    @Test
    void CacheRegenerationRequested_정상_처리() {
        when(idempotencyService.tryAcquire(any(), any())).thenReturn(true);
        String body = """
            {
              "eventId": "evt-regen-001",
              "eventType": "CacheRegenerationRequested",
              "traceId": "trace-regen",
              "idempotencyKey": "cache-regen:shelter:status:101:1744980300",
              "payload": {"cacheKey": "shelter:status:101", "requestedAt": "2026-04-15T15:05:00+09:00"}
            }
            """;
        SQSEvent event = buildEvent("msg-regen", body, 1);

        SQSBatchResponse response = processor.process(event);

        assertThat(response.getBatchItemFailures()).isEmpty();
        verify(eventDispatcher).dispatch(any());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

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
        return envelopeBody(eventType, "key-001", "evt-001");
    }

    private String envelopeBody(String eventType, String idempotencyKey, String eventId) {
        return """
            {
              "eventId": "%s",
              "eventType": "%s",
              "traceId": "trace-001",
              "idempotencyKey": "%s",
              "payload": {"entryId": 1, "shelterId": 101, "changedFields": []}
            }
            """.formatted(eventId, eventType, idempotencyKey);
    }
}
