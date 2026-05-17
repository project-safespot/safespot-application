package com.safespot.scenariosimulator.event;

import com.safespot.scenariosimulator.config.SimulatorSqsProperties;
import com.safespot.scenariosimulator.event.payload.CacheRegenerationRequestedPayload;
import com.safespot.scenariosimulator.event.payload.DisasterAlertCreatedPayload;
import com.safespot.scenariosimulator.event.payload.EvacuationEntryCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatorEventRouterTest {

    private static final String CACHE_QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/123/cache-refresh";
    private static final String READMODEL_QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/123/readmodel-refresh";

    private SimulatorEventRouter router;

    @BeforeEach
    void setUp() {
        SimulatorSqsProperties properties = new SimulatorSqsProperties();
        properties.setCacheRefreshQueueUrl(CACHE_QUEUE_URL);
        properties.setReadmodelRefreshQueueUrl(READMODEL_QUEUE_URL);
        router = new SimulatorEventRouter(properties);
    }

    @Test
    void routesDisasterAlertCreatedToReadmodelRefreshQueue() {
        EventEnvelope<DisasterAlertCreatedPayload> envelope = EventEnvelope.of(
                "DisasterAlertCreated",
                "sim:alert:1:CREATED",
                DisasterAlertCreatedPayload.builder()
                        .alertId(1L)
                        .disasterType("EARTHQUAKE")
                        .region("seoul")
                        .build());

        SimulatorEventRouter.RoutedQueue routedQueue = router.resolve(envelope);

        assertThat(routedQueue.getQueueRole()).isEqualTo("readmodel-refresh");
        assertThat(routedQueue.getQueueUrl()).isEqualTo(READMODEL_QUEUE_URL);
        assertThat(routedQueue.getQueueName()).isEqualTo("readmodel-refresh");
    }

    @Test
    void routesDisasterCacheRegenerationToReadmodelRefreshQueue() {
        EventEnvelope<CacheRegenerationRequestedPayload> envelope = EventEnvelope.of(
                "CacheRegenerationRequested",
                "cache-regen:disaster",
                CacheRegenerationRequestedPayload.builder()
                        .cacheKey("disaster:messages:list:seoul")
                        .cacheKeyFamily("disaster_messages_list")
                        .requestedAt(OffsetDateTime.now())
                        .reason("test")
                        .schemaVersion(1)
                        .build());

        SimulatorEventRouter.RoutedQueue routedQueue = router.resolve(envelope);

        assertThat(routedQueue.getQueueRole()).isEqualTo("readmodel-refresh");
        assertThat(routedQueue.getQueueUrl()).isEqualTo(READMODEL_QUEUE_URL);
        assertThat(routedQueue.getCacheKey()).isEqualTo("disaster:messages:list:seoul");
    }

    @Test
    void routesEvacuationEntryCreatedToCacheRefreshQueue() {
        EventEnvelope<EvacuationEntryCreatedPayload> envelope = EventEnvelope.of(
                "EvacuationEntryCreated",
                "entry:1:ENTERED",
                EvacuationEntryCreatedPayload.builder()
                        .entryId(1L)
                        .shelterId(10L)
                        .nextStatus("ENTERED")
                        .build());

        SimulatorEventRouter.RoutedQueue routedQueue = router.resolve(envelope);

        assertThat(routedQueue.getQueueRole()).isEqualTo("cache-refresh");
        assertThat(routedQueue.getQueueUrl()).isEqualTo(CACHE_QUEUE_URL);
        assertThat(routedQueue.getQueueName()).isEqualTo("cache-refresh");
    }

    @Test
    void routesShelterCacheRegenerationToCacheRefreshQueue() {
        EventEnvelope<CacheRegenerationRequestedPayload> envelope = EventEnvelope.of(
                "CacheRegenerationRequested",
                "cache-regen:shelter",
                CacheRegenerationRequestedPayload.builder()
                        .cacheKey("shelter:status:42")
                        .cacheKeyFamily("shelter_status")
                        .requestedAt(OffsetDateTime.now())
                        .reason("test")
                        .schemaVersion(1)
                        .build());

        SimulatorEventRouter.RoutedQueue routedQueue = router.resolve(envelope);

        assertThat(routedQueue.getQueueRole()).isEqualTo("cache-refresh");
        assertThat(routedQueue.getQueueUrl()).isEqualTo(CACHE_QUEUE_URL);
        assertThat(routedQueue.getCacheKey()).isEqualTo("shelter:status:42");
    }

    @Test
    void unsupportedEventTypeFailsLoudly() {
        EventEnvelope<Object> envelope = EventEnvelope.of(
                "ProactiveScaleRequested",
                "sim:scale:1",
                new Object());

        assertThatThrownBy(() -> router.resolve(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported simulator SQS routing eventType=ProactiveScaleRequested");
    }

    @Test
    void unsupportedCacheKeyFamilyFailsLoudly() {
        EventEnvelope<CacheRegenerationRequestedPayload> envelope = EventEnvelope.of(
                "CacheRegenerationRequested",
                "cache-regen:unknown",
                CacheRegenerationRequestedPayload.builder()
                        .cacheKey("environment:weather:seoul")
                        .cacheKeyFamily("environment_weather")
                        .requestedAt(OffsetDateTime.now())
                        .reason("test")
                        .schemaVersion(1)
                        .build());

        assertThatThrownBy(() -> router.resolve(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported CacheRegenerationRequested cacheKey family");
    }
}
