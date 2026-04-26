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

    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED  = "COMPLETED";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(String idempotencyKey, Duration ttl) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, ttl);
            if (acquired == null) {
                log.error("Idempotency SETNX returned null (abnormal Redis response): key={}", redisKey);
                throw new RedisCacheException("Idempotency SETNX null response: key=" + redisKey);
            }
            if (acquired) {
                return true;
            }
            // key already exists — check state to distinguish COMPLETED vs PROCESSING
            String existing = redisTemplate.opsForValue().get(redisKey);
            if (COMPLETED.equals(existing)) {
                log.info("Idempotency duplicate (COMPLETED): key={}", redisKey);
                return false;
            }
            // PROCESSING or null (race): previous attempt failed, allow retry
            log.info("Idempotency key is PROCESSING (previous failed), allowing retry: key={}", redisKey);
            return true;
        } catch (RedisCacheException e) {
            throw e;
        } catch (Exception e) {
            log.error("Idempotency SETNX failed: key={}", redisKey, e);
            throw new RedisCacheException("Idempotency SETNX failed: key=" + redisKey, e);
        }
    }

    @Override
    public void markCompleted(String idempotencyKey, Duration ttl) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            redisTemplate.opsForValue().set(redisKey, COMPLETED, ttl);
            log.info("Idempotency key marked COMPLETED: key={}", redisKey);
        } catch (Exception e) {
            // markCompleted failure: key stays PROCESSING
            // if SQS redelivers, tryAcquire sees PROCESSING → returns true → re-processes (idempotent writes)
            log.warn("Idempotency markCompleted failed, key stays PROCESSING: key={}", redisKey, e);
        }
    }

    @Override
    public void release(String idempotencyKey) {
        String redisKey = RedisKeyConstants.idempotency(idempotencyKey);
        try {
            redisTemplate.delete(redisKey);
            log.info("Idempotency key released: key={}", redisKey);
        } catch (Exception e) {
            // release failure: key stays PROCESSING
            // next tryAcquire sees PROCESSING → returns true → retry proceeds — NOT message loss
            log.warn("Idempotency release failed, key stays PROCESSING (next delivery will retry): key={}", redisKey, e);
        }
    }
}
