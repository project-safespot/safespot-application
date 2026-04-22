package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqsEventPublisher {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1_000L, 3_000L, 9_000L};

    private final ObjectMapper objectMapper;
    private final Environment environment;

    @Autowired(required = false)
    private SqsClient sqsClient;

    @Value("${safespot.sqs.queue-url:}")
    private String queueUrl;

    private final ExecutorService retryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sqs-retry");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void validateConfiguration() {
        boolean isLocalOrTest = environment.matchesProfiles("local") || environment.matchesProfiles("test");
        if (!isLocalOrTest && (sqsClient == null || queueUrl.isBlank())) {
            throw new IllegalStateException(
                    "safespot.sqs.queue-url must be configured in non-local/test environments. " +
                    "Set the property or add LocalStack endpoint for local development.");
        }
    }

    public void publish(EventEnvelope<?> envelope) {
        if (sqsClient == null || queueUrl.isBlank()) {
            log.warn("[SQS] publisher not configured — event dropped: type={} idempotencyKey={}",
                    envelope.getEventType(), envelope.getIdempotencyKey());
            return;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[SQS] serialization failed — event lost: type={} eventId={}",
                    envelope.getEventType(), envelope.getEventId(), e);
            return;
        }

        retryExecutor.submit(() -> sendWithRetry(envelope, body));
    }

    private void sendWithRetry(EventEnvelope<?> envelope, String body) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[SQS] retry interrupted: type={} eventId={}",
                            envelope.getEventType(), envelope.getEventId());
                    return;
                }
            }
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(body)
                        .build());
                log.info("[SQS] published (attempt={}): type={} eventId={} idempotencyKey={}",
                        attempt + 1, envelope.getEventType(), envelope.getEventId(), envelope.getIdempotencyKey());
                return;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    log.warn("[SQS] publish attempt {}/{} failed, retrying in {}ms: type={} eventId={}",
                            attempt + 1, MAX_RETRIES + 1, BACKOFF_MS[attempt],
                            envelope.getEventType(), envelope.getEventId(), e);
                } else {
                    log.error("[SQS] publish permanently failed after {} attempts — manual recovery required: " +
                                    "eventId={} eventType={} idempotencyKey={} producer={}",
                            MAX_RETRIES + 1, envelope.getEventId(), envelope.getEventType(),
                            envelope.getIdempotencyKey(), envelope.getProducer(), e);
                }
            }
        }
    }
}
