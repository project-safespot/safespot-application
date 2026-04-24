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

    private final StringRedisTemplate redisTemplate;

    public boolean tryPublish(String cacheKey) {
        String suppressKey = "suppress:cache-regeneration:" + hash(cacheKey);
        try {
            Boolean set = redisTemplate.opsForValue().setIfAbsent(suppressKey, "1", SUPPRESS_TTL);
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
