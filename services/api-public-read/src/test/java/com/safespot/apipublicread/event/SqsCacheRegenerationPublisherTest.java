package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsCacheRegenerationPublisherTest {

    @Mock SqsClient sqsClient;
    @Mock CacheKeyFamilyResolver resolver;
    @Mock CacheRegenerationPublishFailureRecorder failureRecorder;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private SqsCacheRegenerationPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/cache-regen-queue";

    @BeforeEach
    void setUp() {
        publisher = new SqsCacheRegenerationPublisher(
                sqsClient, QUEUE_URL, objectMapper, resolver, failureRecorder, meterRegistry);
    }

    @Test
    void publish_supportedKey_sendsMessageToSqsWithCorrectQueueUrl() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-001").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
    }

    @Test
    void publish_sqsSuccess_failureRecorderNotCalled() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-ok").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(failureRecorder, never()).record(any(), anyString());
    }

    @Test
    void publish_sqsFails_failureRecorderCalledWithFullEnvelopeJson() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(failureRecorder).record(any(CacheRegenerationEnvelope.class), jsonCaptor.capture());
        String recorded = jsonCaptor.getValue();
        assertThat(recorded).contains("CacheRegenerationRequested");
        assertThat(recorded).contains("api-public-read");
        assertThat(recorded).contains("traceId");
        assertThat(recorded).contains("idempotencyKey");
        assertThat(recorded).contains("disaster:messages:list:seoul");
        assertThat(recorded).contains("disaster_messages_list");
        assertThat(recorded).contains("cache_miss");
    }

    @Test
    void publish_supportedKey_messageBodyContainsEvent007Fields() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-002").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        String body = captor.getValue().messageBody();
        assertThat(body).contains("CacheRegenerationRequested");
        assertThat(body).contains("api-public-read");
        assertThat(body).contains("traceId");
        assertThat(body).contains("disaster:messages:list:seoul");
        assertThat(body).contains("disaster_messages_list");
        assertThat(body).contains("cache_miss");
        assertThat(body).contains("cache-regen:");
    }

    @Test
    void publish_unsupportedKey_doesNotSendToSqs() {
        when(resolver.resolve("unknown:key")).thenReturn(Optional.empty());

        publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verify(failureRecorder, never()).record(any(), anyString());
    }

    @Test
    void publish_sqsThrowsException_doesNotPropagateException() {
        when(resolver.resolve("shelter:status:101")).thenReturn(Optional.of("shelter_status"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        assertThatCode(() -> publisher.publish("shelter:status:101", CacheRegenerationReason.REDIS_DOWN, "/shelters/{shelterId}"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_redisDownReason_includesRedisDownInBody() {
        when(resolver.resolve("environment:air-quality:seoul"))
                .thenReturn(Optional.of("environment_air_quality"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-003").build());

        publisher.publish("environment:air-quality:seoul", CacheRegenerationReason.REDIS_DOWN, "/air-quality");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().messageBody()).contains("redis_down");
    }
}
