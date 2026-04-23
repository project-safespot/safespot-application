package com.safespot.asyncworker.consumer;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.envelope.EnvelopeParseException;
import com.safespot.asyncworker.envelope.EnvelopeParser;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.idempotency.IdempotencyService;
import com.safespot.asyncworker.idempotency.IdempotencyTtl;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqsBatchProcessor {

    private final EnvelopeParser envelopeParser;
    private final IdempotencyService idempotencyService;
    private final EventDispatcher eventDispatcher;
    private final WorkerMetrics workerMetrics;

    public SQSBatchResponse process(SQSEvent sqsEvent) {
        return process(sqsEvent, null);
    }

    public SQSBatchResponse process(SQSEvent sqsEvent, String awsRequestId) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        List<String> failedEventTypes = new ArrayList<>();

        String batchQueueName = sqsEvent.getRecords().isEmpty() ? "unknown"
            : extractQueueName(sqsEvent.getRecords().get(0));
        workerMetrics.recordBatchSize(sqsEvent.getRecords().size(), batchQueueName);

        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            MessageProcessingResult result = processSingle(message, awsRequestId);
            if (!result.success()) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                    .withItemIdentifier(result.messageId())
                    .build());
                failedEventTypes.add(result.eventType());
            }
        }

        if (!failures.isEmpty()) {
            // 메시지 단위 기준: 실패한 event_type별로 각각 기록
            // 동일 event_type이 복수 실패해도 partial_batch_failure는 type당 1회 증가
            failedEventTypes.stream()
                .distinct()
                .forEach(et -> workerMetrics.incrementPartialBatchFailure(batchQueueName, et));
        }

        return SQSBatchResponse.builder()
            .withBatchItemFailures(failures)
            .build();
    }

    private MessageProcessingResult processSingle(SQSEvent.SQSMessage message, String awsRequestId) {
        long startMs = System.currentTimeMillis();
        String messageId = message.getMessageId();
        String queueName = extractQueueName(message);
        int receiveCount = parseReceiveCount(message);

        EventEnvelope envelope;
        try {
            envelope = envelopeParser.parse(message.getBody());
        } catch (EnvelopeParseException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Envelope parse failed: messageId={}, awsRequestId={}, queueName={}, receiveCount={}, reason={}, errorCode={}",
                messageId, awsRequestId, queueName, receiveCount, e.getMessage(), "ENVELOPE_PARSE_ERROR");
            workerMetrics.incrementFailures("unknown", queueName, "ENVELOPE_PARSE_ERROR");
            workerMetrics.incrementProcessed("unknown", "failure", queueName);
            workerMetrics.recordProcessingDuration("unknown", queueName, durationMs);
            return MessageProcessingResult.failure(messageId, "EnvelopeParseFailure: " + e.getMessage(), "unknown");
        }

        String traceId = envelope.getTraceId();
        String eventId = envelope.getEventId();
        String eventTypeName = envelope.getEventType();
        String idempotencyKey = envelope.getIdempotencyKey();

        boolean acquired = false;
        try {
            EventType eventType = EventType.from(eventTypeName);

            if (exceedsAppRetryLimit(message, eventType)) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.warn("App retry limit exceeded: eventId={}, eventType={}, traceId={}, awsRequestId={}, messageId={}, queueName={}, receiveCount={}",
                    eventId, eventTypeName, traceId, awsRequestId, messageId, queueName, receiveCount);
                workerMetrics.incrementFailures(eventTypeName, queueName, "APP_RETRY_LIMIT_EXCEEDED");
                workerMetrics.incrementProcessed(eventTypeName, "failure", queueName);
                workerMetrics.recordProcessingDuration(eventTypeName, queueName, durationMs);
                return MessageProcessingResult.failure(messageId, "AppRetryLimitExceeded", eventTypeName);
            }

            acquired = idempotencyService.tryAcquire(
                idempotencyKey,
                IdempotencyTtl.forEventType(eventType)
            );
            if (!acquired) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.info("Duplicate event, no-op: eventId={}, eventType={}, traceId={}, awsRequestId={}, messageId={}, queueName={}, idempotencyKey={}",
                    eventId, eventTypeName, traceId, awsRequestId, messageId, queueName, idempotencyKey);
                workerMetrics.incrementIdempotencySkipped(eventTypeName, queueName);
                workerMetrics.incrementProcessed(eventTypeName, "skipped", queueName);
                workerMetrics.recordProcessingDuration(eventTypeName, queueName, durationMs);
                return MessageProcessingResult.success(messageId, eventTypeName);
            }

            try (var ignored = MDC.putCloseable("eventType", eventTypeName)) {
                eventDispatcher.dispatch(envelope);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            workerMetrics.incrementSuccess(eventTypeName, queueName);
            workerMetrics.incrementProcessed(eventTypeName, "success", queueName);
            workerMetrics.recordProcessingDuration(eventTypeName, queueName, durationMs);
            log.info("Event processed: eventId={}, eventType={}, traceId={}, awsRequestId={}, messageId={}, queueName={}, idempotencyKey={}, durationMs={}",
                eventId, eventTypeName, traceId, awsRequestId, messageId, queueName, idempotencyKey, durationMs);
            return MessageProcessingResult.success(messageId, eventTypeName);

        } catch (EventProcessingException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("Event processing failed: eventId={}, eventType={}, traceId={}, awsRequestId={}, messageId={}, queueName={}, idempotencyKey={}, receiveCount={}, reason={}, errorCode={}, durationMs={}",
                eventId, eventTypeName, traceId, awsRequestId, messageId, queueName, idempotencyKey,
                receiveCount, e.getMessage(), "EVENT_PROCESSING_ERROR", durationMs, e);
            workerMetrics.incrementFailures(eventTypeName, queueName, "EVENT_PROCESSING_ERROR");
            workerMetrics.incrementProcessed(eventTypeName, "failure", queueName);
            workerMetrics.recordProcessingDuration(eventTypeName, queueName, durationMs);
            releaseIfAcquired(acquired, idempotencyKey, eventId);
            return MessageProcessingResult.failure(messageId, e.getMessage(), eventTypeName);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            String errorCode = deriveErrorCode(e);
            log.error("Unexpected error: eventId={}, eventType={}, traceId={}, awsRequestId={}, messageId={}, queueName={}, idempotencyKey={}, receiveCount={}, reason={}, errorCode={}, durationMs={}",
                eventId, eventTypeName, traceId, awsRequestId, messageId, queueName, idempotencyKey,
                receiveCount, e.getMessage(), errorCode, durationMs, e);
            workerMetrics.incrementFailures(eventTypeName, queueName, "UNEXPECTED_ERROR");
            workerMetrics.incrementProcessed(eventTypeName, "failure", queueName);
            workerMetrics.recordProcessingDuration(eventTypeName, queueName, durationMs);
            releaseIfAcquired(acquired, idempotencyKey, eventId);
            return MessageProcessingResult.failure(messageId, "UnexpectedError: " + e.getMessage(), eventTypeName);
        }
    }

    private void releaseIfAcquired(boolean acquired, String idempotencyKey, String eventId) {
        if (!acquired) return;
        try {
            idempotencyService.release(idempotencyKey);
        } catch (Exception e) {
            log.warn("Idempotency release failed after processing error: eventId={}, key={}", eventId, idempotencyKey, e);
        }
    }

    private boolean exceedsAppRetryLimit(SQSEvent.SQSMessage message, EventType eventType) {
        return AppRetryPolicy.maxReceiveCount(eventType)
            .map(maxCount -> parseReceiveCount(message) > maxCount)
            .orElse(false);
    }

    private int parseReceiveCount(SQSEvent.SQSMessage message) {
        String countStr = message.getAttributes() != null
            ? message.getAttributes().get("ApproximateReceiveCount")
            : null;
        if (countStr == null) return 1;
        try {
            return Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String extractQueueName(SQSEvent.SQSMessage message) {
        String arn = message.getEventSourceArn();
        if (arn == null) return "unknown";
        int lastColon = arn.lastIndexOf(':');
        return lastColon >= 0 ? arn.substring(lastColon + 1) : "unknown";
    }

    private String deriveErrorCode(Exception e) {
        return e.getClass().getSimpleName()
            .replaceAll("(?i)exception$", "")
            .replaceAll("([A-Z])", "_$1")
            .replaceAll("^_", "")
            .toUpperCase()
            + "_ERROR";
    }
}
