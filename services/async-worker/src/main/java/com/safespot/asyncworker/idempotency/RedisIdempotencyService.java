package com.safespot.asyncworker.idempotency;

import com.safespot.asyncworker.redis.RedisKeyConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisIdempotencyService implements IdempotencyService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(String idempotencyKey, Duration ttl) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", ttl);
            // null은 Redis 비정상 응답이므로 처리 계속 진행(중복 처리 허용)
            return acquired == null || acquired;
        } catch (Exception e) {
            // Redis 장애 시 중복 처리 허용 — 모든 SET은 overwrite-safe
            log.warn("Idempotency SETNX failed, proceeding anyway: key={}", redisKey, e);
            return true;
        }
    }
}
