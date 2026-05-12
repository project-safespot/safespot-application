package com.safespot.externalingestion.publisher.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.IngestionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

/**
 * ingestion.sqs.enabled=true 환경에서만 등록되는 실제 SQS publisher.
 * sqs.enabled=false이면 LoggingCacheEventPublisher가 대신 등록된다.
 */
@ConditionalOnProperty(name = "ingestion.sqs.enabled", havingValue = "true")
@Slf4j
@Component
public class SqsCacheEventPublisher implements CacheEventPublisher {

    private static final String SOURCE = "external-ingestion";

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final IngestionMetrics metrics;
    private final Map<String, String> queueUrls;

    public SqsCacheEventPublisher(
        SqsClient sqsClient,
        ObjectMapper objectMapper,
        IngestionMetrics metrics,
        @Value("${ingestion.sqs.cache-refresh-queue-url}") String cacheRefreshQueueUrl,
        @Value("${ingestion.sqs.readmodel-refresh-queue-url}") String readmodelRefreshQueueUrl,
        @Value("${ingestion.sqs.environment-cache-refresh-queue-url}") String envCacheRefreshQueueUrl
    ) {
        Assert.hasText(cacheRefreshQueueUrl,
            "ingestion.sqs.cache-refresh-queue-url must be set when ingestion.sqs.enabled=true");
        Assert.hasText(readmodelRefreshQueueUrl,
            "ingestion.sqs.readmodel-refresh-queue-url must be set when ingestion.sqs.enabled=true");
        Assert.hasText(envCacheRefreshQueueUrl,
            "ingestion.sqs.environment-cache-refresh-queue-url must be set when ingestion.sqs.enabled=true");

        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.queueUrls = Map.of(
            "cache-refresh", cacheRefreshQueueUrl,
            "disaster-collection", readmodelRefreshQueueUrl,
            "environment-collection", envCacheRefreshQueueUrl
        );
    }

    @Override
    public void publish(IngestionEvent event, String logicalQueueName) {
        String queueUrl = queueUrls.get(logicalQueueName);
        if (queueUrl == null) {
            log.error("[SQS] unknown logical queue eventType={} queueName={}",
                event.getEventType(), logicalQueueName);
            metrics.incrementSqsPublishFailure(SOURCE, logicalQueueName, event.getEventType());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(event);
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
            metrics.incrementSqsPublish(SOURCE, logicalQueueName, event.getEventType());
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(SOURCE, logicalQueueName, event.getEventType());
            log.error("[SQS] publish failed eventId={} eventType={} idempotencyKey={} traceId={} queueName={} queueUrl={}",
                event.getEventId(), event.getEventType(), event.getIdempotencyKey(), event.getTraceId(),
                logicalQueueName, queueUrl, e);
        }
    }
}
