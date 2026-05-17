package com.safespot.apipublicread.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class SuppressWindowService {

    private static final Duration SUPPRESS_TTL = Duration.ofSeconds(30);
    private static final String REGEN_PREFIX = "suppress:cache-regeneration:";
    private static final String DB_FALLBACK_PREFIX = "suppress:db-fallback:";

    private final StringRedisTemplate redisTemplate;

    public boolean tryPublish(String cacheKey) {
        return tryAcquire(REGEN_PREFIX, cacheKey, SUPPRESS_TTL);
    }

    public boolean tryPublish(String cacheKey, Duration ttl) {
        return tryAcquire(REGEN_PREFIX, cacheKey, ttl);
    }

    public boolean tryAllowDbFallback(String cacheKey) {
        return tryAcquire(DB_FALLBACK_PREFIX, cacheKey, SUPPRESS_TTL);
    }

    public boolean tryAllowDbFallback(String cacheKey, Duration ttl) {
        return tryAcquire(DB_FALLBACK_PREFIX, cacheKey, ttl);
    }

    public boolean isDbFallbackSuppressed(String cacheKey) {
        String suppressKey = DB_FALLBACK_PREFIX + hash(cacheKey);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(suppressKey));
        } catch (RedisConnectionFailureException e) {
            log.warn("[Suppress] Redis unavailable for key={}: {}", suppressKey, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[Suppress] Redis error for key={}: {}", suppressKey, e.getMessage());
            return false;
        }
    }

    public void markDbFallbackSuppressed(String cacheKey, Duration ttl) {
        String suppressKey = DB_FALLBACK_PREFIX + hash(cacheKey);
        try {
            redisTemplate.opsForValue().set(suppressKey, "1", ttl);
        } catch (RedisConnectionFailureException e) {
            log.warn("[Suppress] Redis unavailable for key={}: {}", suppressKey, e.getMessage());
        } catch (Exception e) {
            log.warn("[Suppress] Redis error for key={}: {}", suppressKey, e.getMessage());
        }
    }

    private boolean tryAcquire(String prefix, String cacheKey, Duration ttl) {
        String suppressKey = prefix + hash(cacheKey);
        try {
            Boolean set = redisTemplate.opsForValue().setIfAbsent(suppressKey, "1", ttl);
            return Boolean.TRUE.equals(set);
        } catch (RedisConnectionFailureException e) {
            log.warn("[Suppress] Redis unavailable for key={}: {}", suppressKey, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[Suppress] Redis error for key={}: {}", suppressKey, e.getMessage());
            return false;
        }
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
