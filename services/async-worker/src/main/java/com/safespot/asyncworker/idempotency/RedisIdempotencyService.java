package com.safespot.asyncworker.idempotency;

import com.safespot.asyncworker.exception.RedisCacheException;
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
    public void release(String idempotencyKey) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            redisTemplate.delete(redisKey);
            log.info("Idempotency key released: key={}", redisKey);
        } catch (Exception e) {
            // release 실패 시 키가 남음 → 재시도가 duplicate로 차단될 수 있음
            // 이 경우 TTL 만료 후 자연 복구. 원래 실패 결과를 방해하지 않기 위해 예외 전파 안 함
            log.warn("Idempotency release failed, key may persist until TTL expiry: key={}", redisKey, e);
        }
    }

    @Override
    public boolean tryAcquire(String idempotencyKey, Duration ttl) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", ttl);
            if (acquired == null) {
                // null = Redis 비정상 응답 — 멱등성 보장 불가 → 실패 처리
                log.error("Idempotency SETNX returned null (abnormal Redis response): key={}", redisKey);
                throw new RedisCacheException("Idempotency SETNX null response: key=" + redisKey);
            }
            return acquired;
        } catch (RedisCacheException e) {
            throw e;
        } catch (Exception e) {
            // SETNX I/O 실패 시 상위로 전파 → BatchItemFailure → SQS 재시도
            log.error("Idempotency SETNX failed: key={}", redisKey, e);
            throw new RedisCacheException("Idempotency SETNX failed: key=" + redisKey, e);
        }
    }
}
