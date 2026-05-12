package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRegenerationEnvelopeFactoryTest {

    private final CacheRegenerationEnvelopeFactory factory = new CacheRegenerationEnvelopeFactory();

    @ParameterizedTest
    @EnumSource(QueueType.class)
    void build_allQueueTypes_producesRequiredEnvelopeFields(QueueType queueType) {
        CacheRegenerationEnvelope e = factory.build(
                queueType, "some:cache:key", "some_family", CacheRegenerationReason.CACHE_MISS);

        assertThat(e.eventType()).isEqualTo("CacheRegenerationRequested");
        assertThat(e.eventId()).isNotBlank();
        assertThat(e.occurredAt()).isNotBlank();
        assertThat(e.producer()).isEqualTo("api-public-read");
        assertThat(e.traceId()).isNotBlank();
        assertThat(e.idempotencyKey()).startsWith("cache-regen:");
        assertThat(e.payload()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(QueueType.class)
    void build_allQueueTypes_payloadContainsRequiredFields(QueueType queueType) {
        CacheRegenerationEnvelope e = factory.build(
                queueType, "disaster:detail:55", "disaster_detail", CacheRegenerationReason.CACHE_MISS);

        CacheRegenerationPayload p = e.payload();
        assertThat(p.cacheKey()).isEqualTo("disaster:detail:55");
        assertThat(p.cacheKeyFamily()).isEqualTo("disaster_detail");
        assertThat(p.reason()).isEqualTo("cache_miss");
        assertThat(p.requestedAt()).isNotBlank();
        assertThat(p.schemaVersion()).isEqualTo("1");
    }

    @Test
    void build_readmodelRefresh_eventTypeAndPayloadCorrect() {
        CacheRegenerationEnvelope e = factory.build(
                QueueType.READMODEL_REFRESH,
                "disaster:messages:list:seoul", "disaster_messages_list",
                CacheRegenerationReason.CACHE_MISS);

        assertThat(e.eventType()).isEqualTo("CacheRegenerationRequested");
        assertThat(e.producer()).isEqualTo("api-public-read");
        assertThat(e.payload().cacheKeyFamily()).isEqualTo("disaster_messages_list");
        assertThat(e.payload().reason()).isEqualTo("cache_miss");
    }

    @Test
    void build_cacheRefresh_eventTypeAndPayloadCorrect() {
        CacheRegenerationEnvelope e = factory.build(
                QueueType.CACHE_REFRESH,
                "shelter:status:101", "shelter_status",
                CacheRegenerationReason.REDIS_DOWN);

        assertThat(e.eventType()).isEqualTo("CacheRegenerationRequested");
        assertThat(e.payload().cacheKey()).isEqualTo("shelter:status:101");
        assertThat(e.payload().cacheKeyFamily()).isEqualTo("shelter_status");
        assertThat(e.payload().reason()).isEqualTo("redis_down");
    }

    @Test
    void build_environmentCacheRefresh_eventTypeAndPayloadCorrect() {
        CacheRegenerationEnvelope e = factory.build(
                QueueType.ENVIRONMENT_CACHE_REFRESH,
                "environment:weather:seoul", "environment_weather",
                CacheRegenerationReason.CACHE_MISS);

        assertThat(e.eventType()).isEqualTo("CacheRegenerationRequested");
        assertThat(e.payload().cacheKey()).isEqualTo("environment:weather:seoul");
        assertThat(e.payload().reason()).isEqualTo("cache_miss");
    }

    @Test
    void build_readmodelRefreshAndCacheRefresh_differentFamilies_doNotConflict() {
        CacheRegenerationEnvelope disaster = factory.build(
                QueueType.READMODEL_REFRESH,
                "disaster:detail:1", "disaster_detail",
                CacheRegenerationReason.CACHE_MISS);
        CacheRegenerationEnvelope shelter = factory.build(
                QueueType.CACHE_REFRESH,
                "shelter:status:1", "shelter_status",
                CacheRegenerationReason.CACHE_MISS);

        assertThat(disaster.payload().cacheKeyFamily()).isEqualTo("disaster_detail");
        assertThat(shelter.payload().cacheKeyFamily()).isEqualTo("shelter_status");
        assertThat(disaster.eventId()).isNotEqualTo(shelter.eventId());
    }

    @Test
    void build_idempotencyKey_followsExpectedFormat() {
        CacheRegenerationEnvelope e = factory.build(
                QueueType.READMODEL_REFRESH,
                "disaster:detail:99", "disaster_detail",
                CacheRegenerationReason.CACHE_MISS);

        assertThat(e.idempotencyKey()).matches("cache-regen:[a-f0-9]{64}:\\d+");
        String[] parts = e.idempotencyKey().split(":");
        long windowStart = Long.parseLong(parts[parts.length - 1]);
        assertThat(windowStart % 30).isZero();
    }
}
