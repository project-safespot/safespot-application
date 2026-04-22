package com.safespot.asyncworker.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisIdempotencyService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RedisIdempotencyService(redisTemplate);
    }

    @Test
    void SETNX_성공시_true_반환() {
        when(valueOps.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(true);
        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void SETNX_실패시_false_반환() {
        when(valueOps.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(false);
        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void SETNX_null_반환시_true_반환() {
        when(valueOps.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(null);
        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void Redis_장애시_true_반환_처리계속() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis connection refused"));
        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }
}
