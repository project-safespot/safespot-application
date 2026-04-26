package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRegenerationEnvelopeTest {

    @Test
    void build_requiredFields_allPresent() {
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(
                "disaster:messages:list:seoul", "disaster_messages_list", CacheRegenerationReason.CACHE_MISS);

        assertThat(e.eventType()).isEqualTo("CacheRegenerationRequested");
        assertThat(e.eventId()).isNotBlank();
        assertThat(e.occurredAt()).isNotBlank();
        assertThat(e.producer()).isEqualTo("api-public-read");
        assertThat(e.traceId()).isNotBlank();
        assertThat(e.idempotencyKey()).isNotBlank();
        assertThat(e.payload()).isNotNull();
    }

    @Test
    void build_payload_allFieldsPresent() {
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(
                "shelter:status:101", "shelter_status", CacheRegenerationReason.REDIS_DOWN);

        CacheRegenerationPayload p = e.payload();
        assertThat(p.cacheKey()).isEqualTo("shelter:status:101");
        assertThat(p.cacheKeyFamily()).isEqualTo("shelter_status");
        assertThat(p.requestedAt()).isNotBlank();
        assertThat(p.reason()).isEqualTo("redis_down");
        assertThat(p.schemaVersion()).isEqualTo("1");
    }

    @Test
    void build_idempotencyKey_matchesFormat() {
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(
                "environment:weather:seoul", "environment_weather", CacheRegenerationReason.CACHE_MISS);

        // format: cache-regen:{64-char hex}:{epoch seconds divisible by 30}
        assertThat(e.idempotencyKey()).matches("cache-regen:[a-f0-9]{64}:\\d+");
        String[] parts = e.idempotencyKey().split(":");
        long windowStart = Long.parseLong(parts[parts.length - 1]);
        assertThat(windowStart % 30).isZero();
    }

    @Test
    void build_idempotencyKey_sameKeyAndWindow_producesIdenticalHash() {
        String cacheKey = "disaster:messages:list:seoul";
        String hash1 = CacheRegenerationEnvelope.sha256(cacheKey);
        String hash2 = CacheRegenerationEnvelope.sha256(cacheKey);
        assertThat(hash1).isEqualTo(hash2).hasSize(64);
    }

    @Test
    void build_reason_cacheMiss_valueIsSnakeCase() {
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(
                "disaster:messages:list:seoul", "disaster_messages_list", CacheRegenerationReason.CACHE_MISS);
        assertThat(e.payload().reason()).isEqualTo("cache_miss");
    }

    @Test
    void build_reason_redisDown_valueIsSnakeCase() {
        CacheRegenerationEnvelope e = CacheRegenerationEnvelope.build(
                "disaster:messages:list:seoul", "disaster_messages_list", CacheRegenerationReason.REDIS_DOWN);
        assertThat(e.payload().reason()).isEqualTo("redis_down");
    }
}
