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

    @Override
    public void publish(String cacheKey, CacheRegenerationReason reason) {
        Optional<String> family = resolver.resolve(cacheKey);
        if (family.isEmpty()) {
            log.warn("[CacheRegen] unsupported cacheKey={}, skipping SQS publish", cacheKey);
            return;
        }
        CacheRegenerationEnvelope envelope = CacheRegenerationEnvelope.build(cacheKey, family.get(), reason);
        try {
            String body = objectMapper.writeValueAsString(envelope);
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[CacheRegen] sent to SQS idempotencyKey={}", envelope.idempotencyKey());
        } catch (Exception e) {
            log.error("[CacheRegen] SQS publish failed for cacheKey={}: {}", cacheKey, e.getMessage(), e);
        }
    }
}
