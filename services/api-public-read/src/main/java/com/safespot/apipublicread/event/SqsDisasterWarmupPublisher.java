package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Slf4j
@RequiredArgsConstructor
public class SqsDisasterWarmupPublisher implements DisasterWarmupPublisher {

    private final SqsClient sqsClient;
    private final SqsQueueUrlProvider queueUrlProvider;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public void publish(int limit, boolean includeDetails) {
        DisasterWarmupEnvelope envelope = DisasterWarmupEnvelope.build(limit, includeDetails);
        String queueUrl = queueUrlProvider.get(QueueType.READMODEL_REFRESH);
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("[DisasterWarmup] serialization failed: limit={}, includeDetails={}", limit, includeDetails, e);
            recordMetric("failure");
            return;
        }

        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            log.info("[DisasterWarmup] published: limit={}, includeDetails={}, traceId={}, idempotencyKey={}",
                    limit, includeDetails, envelope.traceId(), envelope.idempotencyKey());
            recordMetric("success");
        } catch (Exception e) {
            log.error("[DisasterWarmup] SQS send failed: limit={}, includeDetails={}, eventId={}, traceId={}: {}",
                    limit, includeDetails, envelope.eventId(), envelope.traceId(), e.getMessage());
            recordMetric("failure");
        }
    }

    private void recordMetric(String result) {
        meterRegistry.counter("safespot_disaster_warmup_publish_total",
                "service", "api-public-read",
                "result", result
        ).increment();
    }
}
