package com.safespot.apipublicread.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

public record CacheRegenerationEnvelope(
        String eventType,
        String eventId,
        String occurredAt,
        String producer,
        String traceId,
        String idempotencyKey,
        CacheRegenerationPayload payload
) {
    private static final String EVENT_TYPE = "CacheRegenerationRequested";
    private static final String PRODUCER = "api-public-read";
    private static final String SCHEMA_VERSION = "1";

    public static CacheRegenerationEnvelope build(String cacheKey, String cacheKeyFamily,
                                                   CacheRegenerationReason reason) {
        Instant now = Instant.now();
        long windowStart = (now.getEpochSecond() / 30) * 30;
        String cacheKeyHash = sha256(cacheKey);
        String idempotencyKey = "cache-regen:" + cacheKeyHash + ":" + windowStart;

        CacheRegenerationPayload payload = new CacheRegenerationPayload(
                cacheKey,
                cacheKeyFamily,
                now.toString(),
                reason.value(),
                SCHEMA_VERSION
        );

        return new CacheRegenerationEnvelope(
                EVENT_TYPE,
                UUID.randomUUID().toString(),
                now.toString(),
                PRODUCER,
                UUID.randomUUID().toString(),
                idempotencyKey,
                payload
        );
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
