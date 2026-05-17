package com.safespot.apipublicread.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SuppressWindowService {

    private static final Duration SUPPRESS_TTL = Duration.ofSeconds(30);
    private static final String REGEN_PREFIX = "suppress:cache-regeneration:";
    private static final String DB_FALLBACK_PREFIX = "suppress:db-fallback:";
    private static final Logger log = LoggerFactory.getLogger(SuppressWindowService.class);

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration localDenyTtl;
    private final ConcurrentMap<String, Long> localDenyUntil = new ConcurrentHashMap<>();

    public SuppressWindowService(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${safespot.public-read.suppress-window.local-deny-ttl-ms:1000}") long localDenyTtlMs
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.localDenyTtl = Duration.ofMillis(localDenyTtlMs);
    }

    SuppressWindowService(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this(redisTemplate, meterRegistry, 1_000);
    }

    public boolean tryPublish(String cacheKey) {
        return tryAcquire(REGEN_PREFIX, cacheKey, SUPPRESS_TTL);
    }

    public boolean tryAllowDbFallback(String cacheKey) {
        return tryAcquire(DB_FALLBACK_PREFIX, cacheKey, SUPPRESS_TTL);
    }

    private boolean tryAcquire(String prefix, String cacheKey, Duration ttl) {
        String cacheKeyHash = hash(cacheKey);
        String suppressKey = prefix + cacheKeyHash;
        String localKey = prefix + ":" + cacheKeyHash;
        if (isLocallyDenied(localKey)) {
            meterRegistry.counter("safespot.suppress.local.deny.hit",
                    "service", "api-public-read",
                    "scope", scope(prefix)
            ).increment();
            return false;
        }
        try {
            Boolean set = redisTemplate.opsForValue().setIfAbsent(suppressKey, "1", ttl);
            if (Boolean.TRUE.equals(set)) {
                meterRegistry.counter("safespot.suppress.redis.acquire",
                        "service", "api-public-read",
                        "scope", scope(prefix),
                        "result", "allowed"
                ).increment();
                return true;
            }
            meterRegistry.counter("safespot.suppress.redis.acquire",
                    "service", "api-public-read",
                    "scope", scope(prefix),
                    "result", "denied"
            ).increment();
            denyLocally(localKey);
            return false;
        } catch (RedisConnectionFailureException e) {
            log.warn("[Suppress] Redis unavailable for key={}: {}", suppressKey, e.getMessage());
            meterRegistry.counter("safespot.suppress.redis.acquire",
                    "service", "api-public-read",
                    "scope", scope(prefix),
                    "result", "error"
            ).increment();
            denyLocally(localKey);
            return false;
        } catch (Exception e) {
            log.warn("[Suppress] Redis error for key={}: {}", suppressKey, e.getMessage());
            meterRegistry.counter("safespot.suppress.redis.acquire",
                    "service", "api-public-read",
                    "scope", scope(prefix),
                    "result", "error"
            ).increment();
            denyLocally(localKey);
            return false;
        }
    }

    private boolean isLocallyDenied(String localKey) {
        Long deniedUntil = localDenyUntil.get(localKey);
        if (deniedUntil == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (deniedUntil <= now) {
            localDenyUntil.remove(localKey, deniedUntil);
            return false;
        }
        return true;
    }

    private void denyLocally(String localKey) {
        localDenyUntil.put(localKey, System.currentTimeMillis() + localDenyTtl.toMillis());
    }

    private static String scope(String prefix) {
        return prefix.equals(REGEN_PREFIX) ? "cache_regeneration" : "db_fallback";
    }

    private static String hash(String input) {
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
