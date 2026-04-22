package com.safespot.apicore.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Slf4j
@Service
public class SqsEventPublisher {

    @Autowired(required = false)
    private SqsClient sqsClient;

    @Value("${safespot.sqs.queue-url:}")
    private String queueUrl;

    private final ObjectMapper objectMapper;

    public SqsEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void publish(EventEnvelope<?> envelope) {
        if (sqsClient == null || queueUrl.isBlank()) {
            log.warn("[SQS] publisher not configured — event dropped: type={} idempotencyKey={}",
                    envelope.getEventType(), envelope.getIdempotencyKey());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(envelope);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[SQS] published type={} eventId={} idempotencyKey={}",
                    envelope.getEventType(), envelope.getEventId(), envelope.getIdempotencyKey());
        } catch (Exception e) {
            log.error("[SQS] publish failed type={} eventId={}",
                    envelope.getEventType(), envelope.getEventId(), e);
        }
    }
}
