package com.safespot.scenariosimulator.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorEventPublisher {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1_000L, 3_000L, 9_000L};

    private final ObjectMapper objectMapper;
    private final SimulatorMetrics metrics;
    private final SimulatorEventRouter eventRouter;
    private final ObjectProvider<SqsClient> sqsClientProvider;

    public void publish(EventEnvelope<?> envelope) {
        SimulatorEventRouter.RoutedQueue routedQueue = eventRouter.resolve(envelope);
        SqsClient sqsClient = sqsClientProvider.getIfAvailable();

        if (sqsClient == null) {
            log.warn("[SIM-SQS] publisher not configured: eventType={} queueRole={} selectedQueueName={} cacheKey={}",
                    envelope.getEventType(), routedQueue.getQueueRole(), routedQueue.getQueueName(),
                    routedQueue.getCacheKey());
            return;
        }
        if (routedQueue.getQueueUrl().isBlank()) {
            throw new IllegalStateException("SQS queue URL not configured for role=" + routedQueue.getQueueRole());
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[SIM-SQS] serialization failed: eventType={} eventId={}",
                    envelope.getEventType(), envelope.getEventId(), e);
            metrics.incFailure("serialization_error");
            return;
        }

        sendWithRetry(sqsClient, envelope, routedQueue, body);
        metrics.incEventsPublished(envelope.getEventType());
    }

    private void sendWithRetry(
            SqsClient sqsClient,
            EventEnvelope<?> envelope,
            SimulatorEventRouter.RoutedQueue routedQueue,
            String body) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[SIM-SQS] retry interrupted: eventType={} queueRole={}",
                            envelope.getEventType(), routedQueue.getQueueRole());
                    metrics.incFailure("retry_interrupted");
                    return;
                }
            }
            try {
                SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(routedQueue.getQueueUrl())
                        .messageBody(body)
                        .build());
                log.info("[SIM-SQS] published: eventType={} queueRole={} selectedQueueName={} cacheKey={} eventId={} idempotencyKey={} messageId={} attempt={}",
                        envelope.getEventType(), routedQueue.getQueueRole(), routedQueue.getQueueName(),
                        routedQueue.getCacheKey(), envelope.getEventId(), envelope.getIdempotencyKey(),
                        response.messageId(), attempt + 1);
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[SIM-SQS] send failed, retrying: eventType={} queueRole={} selectedQueueName={} cacheKey={} eventId={} attempt={}/{}",
                            envelope.getEventType(), routedQueue.getQueueRole(), routedQueue.getQueueName(),
                            routedQueue.getCacheKey(), envelope.getEventId(), attempt + 1, MAX_RETRIES + 1, e);
                } else {
                    log.error("[SIM-SQS] permanent failure: eventType={} queueRole={} selectedQueueName={} cacheKey={} eventId={} lastError={}",
                            envelope.getEventType(), routedQueue.getQueueRole(), routedQueue.getQueueName(),
                            routedQueue.getCacheKey(), envelope.getEventId(), e.getMessage(), e);
                    metrics.incFailure("sqs_permanent_failure");
                }
            }
        }
    }
}
