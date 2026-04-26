package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class SqsCacheRegenerationPublisher implements CacheRegenerationPublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper objectMapper;
    private final CacheKeyFamilyResolver resolver;
    private final CacheRegenerationPublishFailureRecorder failureRecorder;

    @Override
    public void publish(String cacheKey, CacheRegenerationReason reason) {
        Optional<String> family = resolver.resolve(cacheKey);
        if (family.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheKey={}, skipping SQS publish", cacheKey);
            return;
        }
        CacheRegenerationEnvelope envelope = CacheRegenerationEnvelope.build(cacheKey, family.get(), reason);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[CacheRegen] envelope serialization failed idempotencyKey={} cacheKey={}: {}",
                    envelope.idempotencyKey(), cacheKey, e.getMessage(), e);
            return;
        }
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] sent to SQS idempotencyKey={} traceId={}",
                    envelope.idempotencyKey(), envelope.traceId());
        } catch (Exception e) {
            log.error("[CacheRegen] SQS send failed eventId={} eventType={} idempotencyKey={} traceId={} cacheKeyFamily={}: {}",
                    envelope.eventId(), envelope.eventType(), envelope.idempotencyKey(),
                    envelope.traceId(), envelope.payload().cacheKeyFamily(), e.getMessage());
            failureRecorder.record(envelope, body);
        }
    }
}
