package com.safespot.apipublicread.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsDisasterWarmupPublisherTest {

    @Mock private SqsClient sqsClient;
    @Mock private SqsQueueUrlProvider queueUrlProvider;

    private SqsDisasterWarmupPublisher publisher;

    @BeforeEach
    void setUp() {
        when(queueUrlProvider.get(QueueType.READMODEL_REFRESH)).thenReturn("https://sqs.test/readmodel-queue");
        publisher = new SqsDisasterWarmupPublisher(sqsClient, queueUrlProvider, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @Test
    void publish_sendMessage_toReadmodelRefreshQueue() {
        publisher.publish(10, true);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo("https://sqs.test/readmodel-queue");
    }

    @Test
    void publish_messageBody_containsEventTypeAndPayload() throws Exception {
        publisher.publish(5, false);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        String body = captor.getValue().messageBody();
        assertThat(body).contains("DisasterReadModelWarmupRequested");
        assertThat(body).contains("\"limit\":5");
        assertThat(body).contains("\"includeDetails\":false");
    }

    @Test
    void publish_sqs_failure_doesNotThrow() {
        doThrow(new RuntimeException("SQS error")).when(sqsClient).sendMessage(any(SendMessageRequest.class));

        publisher.publish(10, true);
        // no exception propagated
    }

    @Test
    void publish_idempotencyKey_containsWindowStart() {
        publisher.publish(10, true);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().messageBody()).contains("disaster-warmup:all:");
    }
}
