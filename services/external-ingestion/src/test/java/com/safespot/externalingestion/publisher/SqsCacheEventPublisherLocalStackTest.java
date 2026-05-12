package com.safespot.externalingestion.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.publisher.event.EnvironmentDataCollectedEvent;
import com.safespot.externalingestion.publisher.event.ShelterDataCollectedEvent;
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
 * LocalStack SQS로 SqsCacheEventPublisher.publish()가 3개 queue에 각각 정확히 전송하는지 검증.
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
    private String cacheRefreshQueueUrl;
    private String readmodelRefreshQueueUrl;
    private String envCacheRefreshQueueUrl;

    @BeforeEach
    void setup() {
        sqsClient = SqsClient.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
            .region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();

        long ts = System.nanoTime();
        cacheRefreshQueueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("cache-refresh-" + ts).build()
        ).queueUrl();

        readmodelRefreshQueueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("readmodel-refresh-" + ts).build()
        ).queueUrl();

        envCacheRefreshQueueUrl = sqsClient.createQueue(
            CreateQueueRequest.builder().queueName("env-cache-refresh-" + ts).build()
        ).queueUrl();

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());
        publisher = new SqsCacheEventPublisher(sqsClient, objectMapper, metrics,
            cacheRefreshQueueUrl, readmodelRefreshQueueUrl, envCacheRefreshQueueUrl);
    }

    @Test
    void publish_shelterEvent_sendsMessageToCacheRefreshQueue() {
        ShelterDataCollectedEvent event = new ShelterDataCollectedEvent(
            "trace-shelter", "EARTHQUAKE", 5,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(event, "cache-refresh");

        ReceiveMessageResponse response = receive(cacheRefreshQueueUrl);
        assertThat(response.messages()).hasSize(1);
        String body = response.messages().get(0).body();
        assertThat(body).contains("ShelterDataCollected");
        assertThat(body).contains("EARTHQUAKE");
    }

    @Test
    void publish_disasterEvent_sendsMessageToReadmodelRefreshQueue() {
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-disaster", "EARTHQUAKE", "seoul", List.of(1L, 2L), false,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(event, "disaster-collection");

        ReceiveMessageResponse response = receive(readmodelRefreshQueueUrl);
        assertThat(response.messages()).hasSize(1);
        String body = response.messages().get(0).body();
        assertThat(body).contains("DisasterDataCollected");
        assertThat(body).contains("\"seoul\"");
        assertThat(body).contains("EARTHQUAKE");
    }

    @Test
    void publish_environmentEvent_sendsMessageToEnvCacheRefreshQueue() {
        EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
            "trace-env", "AIR_QUALITY", "seoul", "1h",
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(event, "environment-collection");

        ReceiveMessageResponse response = receive(envCacheRefreshQueueUrl);
        assertThat(response.messages()).hasSize(1);
        String body = response.messages().get(0).body();
        assertThat(body).contains("EnvironmentDataCollected");
        assertThat(body).contains("AIR_QUALITY");
    }

    @Test
    void publish_unknownQueue_doesNotSendToAnyQueue() {
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-unknown", "EARTHQUAKE", "seoul", List.of(1L), false,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(event, "unknown-queue");

        // none of the 3 queues should receive a message
        assertThat(receive(cacheRefreshQueueUrl).messages()).isEmpty();
        assertThat(receive(readmodelRefreshQueueUrl).messages()).isEmpty();
        assertThat(receive(envCacheRefreshQueueUrl).messages()).isEmpty();
    }

    @Test
    void queues_are_independent_each_message_lands_in_correct_queue() {
        ShelterDataCollectedEvent shelterEvent = new ShelterDataCollectedEvent(
            "trace-s", "FLOOD", 3,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        DisasterDataCollectedEvent disasterEvent = new DisasterDataCollectedEvent(
            "trace-d", "LANDSLIDE", "seoul", List.of(10L), false,
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        publisher.publish(shelterEvent, "cache-refresh");
        publisher.publish(disasterEvent, "disaster-collection");

        assertThat(receive(cacheRefreshQueueUrl).messages()).hasSize(1);
        assertThat(receive(readmodelRefreshQueueUrl).messages()).hasSize(1);
        assertThat(receive(envCacheRefreshQueueUrl).messages()).isEmpty();
    }

    private ReceiveMessageResponse receive(String queueUrl) {
        return sqsClient.receiveMessage(ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(5)
            .waitTimeSeconds(2)
            .build());
    }
}
