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
        @Value("${ingestion.sqs.disaster-cache-queue-url}") String disasterQueueUrl,
        @Value("${ingestion.sqs.environment-cache-queue-url}") String environmentQueueUrl
    ) {
        Assert.hasText(disasterQueueUrl,
            "ingestion.sqs.disaster-cache-queue-url must be set when ingestion.sqs.enabled=true");
        Assert.hasText(environmentQueueUrl,
            "ingestion.sqs.environment-cache-queue-url must be set when ingestion.sqs.enabled=true");

        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.queueUrls = Map.of(
            "disaster-collection", disasterQueueUrl,
            "environment-collection", environmentQueueUrl
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
