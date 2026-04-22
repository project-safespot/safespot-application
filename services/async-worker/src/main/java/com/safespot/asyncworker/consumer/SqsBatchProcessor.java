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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public SQSBatchResponse process(SQSEvent sqsEvent) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            MessageProcessingResult result = processSingle(message);
            if (!result.success()) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                    .withItemIdentifier(result.messageId())
                    .build());
            }
        }

        return SQSBatchResponse.builder()
            .withBatchItemFailures(failures)
            .build();
    }

    private MessageProcessingResult processSingle(SQSEvent.SQSMessage message) {
        String messageId = message.getMessageId();

        EventEnvelope envelope;
        try {
            envelope = envelopeParser.parse(message.getBody());
        } catch (EnvelopeParseException e) {
            log.error("Envelope parse failed, sending to DLQ immediately: messageId={}, reason={}",
                messageId, e.getMessage());
            return MessageProcessingResult.failure(messageId, "EnvelopeParseFailure: " + e.getMessage());
        }

        String traceId = envelope.getTraceId();
        String eventId = envelope.getEventId();

        try {
            EventType eventType = EventType.from(envelope.getEventType());

            if (exceedsAppRetryLimit(message, eventType)) {
                log.warn("App retry limit exceeded: eventId={}, eventType={}, traceId={}",
                    eventId, eventType, traceId);
                return MessageProcessingResult.failure(messageId, "AppRetryLimitExceeded");
            }

            boolean acquired = idempotencyService.tryAcquire(
                envelope.getIdempotencyKey(),
                IdempotencyTtl.forEventType(eventType)
            );
            if (!acquired) {
                log.info("Duplicate event, no-op: eventId={}, idempotencyKey={}, traceId={}",
                    eventId, envelope.getIdempotencyKey(), traceId);
                return MessageProcessingResult.success(messageId);
            }

            eventDispatcher.dispatch(envelope);
            log.info("Event processed: eventId={}, eventType={}, traceId={}",
                eventId, eventType, traceId);
            return MessageProcessingResult.success(messageId);

        } catch (EventProcessingException e) {
            log.error("Event processing failed: eventId={}, traceId={}, reason={}",
                eventId, traceId, e.getMessage(), e);
            return MessageProcessingResult.failure(messageId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error: eventId={}, traceId={}", eventId, traceId, e);
            return MessageProcessingResult.failure(messageId, "UnexpectedError: " + e.getMessage());
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
}
