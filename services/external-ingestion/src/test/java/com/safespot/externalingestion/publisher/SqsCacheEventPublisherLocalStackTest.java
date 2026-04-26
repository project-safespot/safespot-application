package com.safespot.externalingestion.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.publisher.impl.SqsCacheEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LocalStack SQS로 SqsCacheEventPublisher.publish()가 실제 메시지를 queue에 전송하는지 검증.
 * Docker가 없으면 SKIPPED (빌드는 통과).
 */
@Testcontainers(disabledWithoutDocker = true)
class SqsCacheEventPublisherLocalStackTest {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3"))
        .withServices(LocalStackContainer.Service.SQS);

    private SqsClient sqsClient;
    private SqsCacheEventPublisher publisher;
    private String disasterQueueUrl;
    private String environmentQueueUrl;

    @BeforeEach
    void setup() {
        sqsClient = SqsClient.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();

        disasterQueueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("disaster-cache-" + System.nanoTime()).build()
        ).queueUrl();

        environmentQueueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("environment-cache-" + System.nanoTime()).build()
        ).queueUrl();

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        publisher = new SqsCacheEventPublisher(sqsClient, objectMapper, metrics,
            disasterQueueUrl, environmentQueueUrl);
    }

    @Test
    void publish_sendsMessageToDisasterQueue() {
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-localstack", "EARTHQUAKE", "seoul", List.of(1L, 2L), false,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(event, "disaster-collection");

        ReceiveMessageResponse response = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(disasterQueueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(2)
                .build()
        );

        assertThat(response.messages()).hasSize(1);
        String body = response.messages().get(0).body();
        assertThat(body).contains("DisasterDataCollected");
        assertThat(body).contains("\"seoul\"");
        assertThat(body).contains("EARTHQUAKE");
    }
}
