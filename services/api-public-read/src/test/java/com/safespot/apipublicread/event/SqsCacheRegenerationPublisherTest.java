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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsCacheRegenerationPublisherTest {

    @Mock SqsClient sqsClient;
    @Mock CacheKeyFamilyResolver familyResolver;
    @Mock CacheRegenerationRouteResolver routeResolver;
    @Mock CacheRegenerationEnvelopeFactory envelopeFactory;
    @Mock SqsQueueUrlProvider queueUrlProvider;
    @Mock CacheRegenerationPublishFailureRecorder failureRecorder;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SqsCacheRegenerationPublisher publisher;

    private static final String CACHE_REFRESH_URL = "http://localhost:4566/000000000000/cache-refresh-queue";
    private static final String READMODEL_REFRESH_URL = "http://localhost:4566/000000000000/readmodel-refresh-queue";
    private static final String ENV_CACHE_REFRESH_URL = "http://localhost:4566/000000000000/env-cache-refresh-queue";

    @BeforeEach
    void setUp() {
        publisher = new SqsCacheRegenerationPublisher(
                sqsClient, queueUrlProvider, objectMapper,
                familyResolver, routeResolver, envelopeFactory,
                failureRecorder, meterRegistry);

        lenient().when(queueUrlProvider.get(QueueType.CACHE_REFRESH)).thenReturn(CACHE_REFRESH_URL);
        lenient().when(queueUrlProvider.get(QueueType.READMODEL_REFRESH)).thenReturn(READMODEL_REFRESH_URL);
        lenient().when(queueUrlProvider.get(QueueType.ENVIRONMENT_CACHE_REFRESH)).thenReturn(ENV_CACHE_REFRESH_URL);

        lenient().when(envelopeFactory.build(any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> CacheRegenerationEnvelope.build(
                        inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private void stubRoute(String cacheKey, String family, QueueType queueType) {
        when(familyResolver.resolve(cacheKey)).thenReturn(Optional.of(family));
        when(routeResolver.resolve(family)).thenReturn(Optional.of(new CacheRegenerationRoute(queueType)));
    }

    // ── queue routing ─────────────────────────────────────────────────────────

    @Test
    void publish_disasterDetailMiss_sendsToReadmodelRefreshQueue() {
        stubRoute("disaster:detail:42", "disaster_detail", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-001").build());

        publisher.publish("disaster:detail:42", CacheRegenerationReason.CACHE_MISS, "/disasters");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(READMODEL_REFRESH_URL);
    }

    @Test
    void publish_disasterMessagesListMiss_sendsToReadmodelRefreshQueue() {
        stubRoute("disaster:messages:list:seoul", "disaster_messages_list", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-002").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(READMODEL_REFRESH_URL);
    }

    @Test
    void publish_shelterStatusMiss_sendsToCacheRefreshQueue() {
        stubRoute("shelter:status:101", "shelter_status", QueueType.CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-003").build());

        publisher.publish("shelter:status:101", CacheRegenerationReason.CACHE_MISS, "/shelters/{shelterId}");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(CACHE_REFRESH_URL);
    }

    @Test
    void publish_environmentWeatherMiss_sendsToEnvironmentCacheRefreshQueue() {
        stubRoute("environment:weather:seoul", "environment_weather", QueueType.ENVIRONMENT_CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-004").build());

        publisher.publish("environment:weather:seoul", CacheRegenerationReason.CACHE_MISS, "/weather-alerts");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(ENV_CACHE_REFRESH_URL);
    }

    @Test
    void publish_environmentAirQualityMiss_sendsToEnvironmentCacheRefreshQueue() {
        stubRoute("environment:air-quality:seoul", "environment_air_quality", QueueType.ENVIRONMENT_CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-005").build());

        publisher.publish("environment:air-quality:seoul", CacheRegenerationReason.CACHE_MISS, "/air-quality");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(ENV_CACHE_REFRESH_URL);
    }

    // ── success / failure recorder ────────────────────────────────────────────

    @Test
    void publish_sqsSuccess_failureRecorderNotCalled() {
        stubRoute("disaster:messages:list:seoul", "disaster_messages_list", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-ok").build());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(failureRecorder, never()).record(any(), anyString(), any(), anyString());
    }

    @Test
    void publish_sqsFails_failureRecorderCalledWithQueueAndEnvelopeType() {
        stubRoute("disaster:messages:list:seoul", "disaster_messages_list", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(failureRecorder).record(any(CacheRegenerationEnvelope.class), anyString(),
                eq(QueueType.READMODEL_REFRESH), eq("readmodel-refresh"));
    }

    @Test
    void publish_sqsFails_failureRecorderBodyContainsEnvelopeFields() {
        stubRoute("disaster:messages:list:seoul", "disaster_messages_list", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(failureRecorder).record(any(CacheRegenerationEnvelope.class), jsonCaptor.capture(),
                eq(QueueType.READMODEL_REFRESH), anyString());
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
    void publish_shelterSqsFails_failureRecorderCalledWithCacheRefreshType() {
        stubRoute("shelter:status:101", "shelter_status", QueueType.CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        publisher.publish("shelter:status:101", CacheRegenerationReason.CACHE_MISS, "/shelters/{shelterId}");

        verify(failureRecorder).record(any(CacheRegenerationEnvelope.class), anyString(),
                eq(QueueType.CACHE_REFRESH), eq("cache-refresh"));
    }

    // ── unsupported / no-route ────────────────────────────────────────────────

    @Test
    void publish_unsupportedKey_doesNotSendToSqs() {
        when(familyResolver.resolve("unknown:key")).thenReturn(Optional.empty());

        publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verify(failureRecorder, never()).record(any(), anyString(), any(), anyString());
    }

    @Test
    void publish_unsupportedKey_incrementsUnsupportedMetric() {
        when(familyResolver.resolve("unknown:key")).thenReturn(Optional.empty());

        publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS, "/test");

        assertThat(meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", "unknown",
                "queue", "unknown",
                "envelope", "unknown",
                "reason", "cache_miss",
                "result", "unsupported").count()).isEqualTo(1.0);
    }

    @Test
    void publish_familyWithNoQueueRoute_doesNotSend() {
        when(familyResolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        when(routeResolver.resolve("disaster_messages_list")).thenReturn(Optional.empty());

        publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts");

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verify(failureRecorder, never()).record(any(), anyString(), any(), anyString());
    }

    // ── exception safety ──────────────────────────────────────────────────────

    @Test
    void publish_sqsThrowsException_doesNotPropagateException() {
        stubRoute("shelter:status:101", "shelter_status", QueueType.CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS unavailable"));

        assertThatCode(() -> publisher.publish("shelter:status:101", CacheRegenerationReason.REDIS_DOWN, "/shelters/{shelterId}"))
                .doesNotThrowAnyException();
    }

    // ── message body ──────────────────────────────────────────────────────────

    @Test
    void publish_messageBodyContainsRequiredEnvelopeFields() {
        stubRoute("disaster:messages:list:seoul", "disaster_messages_list", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-body").build());

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
    void publish_redisDownReason_bodyContainsRedisDown() {
        stubRoute("environment:air-quality:seoul", "environment_air_quality", QueueType.ENVIRONMENT_CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-down").build());

        publisher.publish("environment:air-quality:seoul", CacheRegenerationReason.REDIS_DOWN, "/air-quality");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertThat(captor.getValue().messageBody()).contains("redis_down");
    }

    // ── metrics ───────────────────────────────────────────────────────────────

    @Test
    void publish_success_incrementsSuccessMetric() {
        stubRoute("shelter:status:101", "shelter_status", QueueType.CACHE_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-m1").build());

        publisher.publish("shelter:status:101", CacheRegenerationReason.CACHE_MISS, "/shelters/{shelterId}");

        assertThat(meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", "shelter_status",
                "queue", "cache-refresh",
                "envelope", "cache-refresh",
                "reason", "cache_miss",
                "result", "success").count()).isEqualTo(1.0);
    }

    @Test
    void publish_failure_incrementsFailureMetric() {
        stubRoute("disaster:detail:55", "disaster_detail", QueueType.READMODEL_REFRESH);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(new RuntimeException("SQS down"));

        publisher.publish("disaster:detail:55", CacheRegenerationReason.CACHE_MISS, "/disasters");

        assertThat(meterRegistry.counter("safespot_cache_regeneration_publish_total",
                "service", "api-public-read",
                "cache", "disaster_detail",
                "queue", "readmodel-refresh",
                "envelope", "readmodel-refresh",
                "reason", "cache_miss",
                "result", "failure").count()).isEqualTo(1.0);
    }
}
