package com.safespot.scenariosimulator.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorEventPublisher {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1_000L, 3_000L, 9_000L};

    private final ObjectMapper objectMapper;
    private final SimulatorMetrics metrics;

    @Autowired(required = false)
    private SqsClient sqsClient;

    @Value("${simulator.sqs.queue-url:}")
    private String queueUrl;

    public void publish(EventEnvelope<?> envelope) {
        if (sqsClient == null || queueUrl.isBlank()) {
            log.warn("[SIM-SQS] publisher not configured — event skipped: type={} idempotencyKey={}",
                    envelope.getEventType(), envelope.getIdempotencyKey());
            return;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[SIM-SQS] serialization failed: type={} eventId={}",
                    envelope.getEventType(), envelope.getEventId(), e);
            metrics.incFailure("serialization_error");
            return;
        }

        sendWithRetry(envelope, body);
        metrics.incEventsPublished(envelope.getEventType());
    }

    private void sendWithRetry(EventEnvelope<?> envelope, String body) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[SIM-SQS] retry interrupted: type={}", envelope.getEventType());
                    metrics.incFailure("retry_interrupted");
                    return;
                }
            }
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .build());
                log.info("[SIM-SQS] published (attempt={}): type={} eventId={} idempotencyKey={}",
                        attempt + 1, envelope.getEventType(), envelope.getEventId(),
                        envelope.getIdempotencyKey());
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[SIM-SQS] attempt {}/{} failed, retrying: type={} eventId={}",
                            attempt + 1, MAX_RETRIES + 1, envelope.getEventType(), envelope.getEventId(), e);
                } else {
                    log.error("[SIM-SQS] permanent failure: type={} eventId={} lastError={}",
                            envelope.getEventType(), envelope.getEventId(), e.getMessage(), e);
                    metrics.incFailure("sqs_permanent_failure");
                }
            }
        }
    }
}
