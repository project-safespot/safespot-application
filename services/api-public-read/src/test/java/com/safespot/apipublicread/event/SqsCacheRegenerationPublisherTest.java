package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsCacheRegenerationPublisherTest {

    @Mock SqsClient sqsClient;
    @Mock CacheKeyFamilyResolver resolver;

    private SqsCacheRegenerationPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/cache-regen-queue";

    @BeforeEach
    void setUp() {
        publisher = new SqsCacheRegenerationPublisher(sqsClient, QUEUE_URL, objectMapper, resolver);
    }

    @Test
    void publish_supportedKey_sendsMessageToSqsWithCorrectQueueUrl() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-001").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        SendMessageRequest req = captor.getValue();
        assertThat(req.queueUrl()).isEqualTo(QUEUE_URL);
    }

    @Test
    void publish_supportedKey_messageBodyContainsEvent007Fields() throws Exception {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-002").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS);

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

        publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS);

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void publish_sqsThrowsException_doesNotPropagateException() {
        when(resolver.resolve("shelter:status:101")).thenReturn(Optional.of("shelter_status"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        assertThatCode(() -> publisher.publish("shelter:status:101", CacheRegenerationReason.REDIS_DOWN))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_redisDownReason_includesRedisDownInBody() throws Exception {
        when(resolver.resolve("environment:air-quality:seoul"))
                .thenReturn(Optional.of("environment_air_quality"));
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-003").build());

        publisher.publish("environment:air-quality:seoul", CacheRegenerationReason.REDIS_DOWN);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().messageBody()).contains("redis_down");
    }
}
