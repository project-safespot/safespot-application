package com.safespot.asyncworker.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import com.safespot.asyncworker.exception.RedisCacheException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisIdempotencyService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
    void SETNX_null_반환시_RedisCacheException_전파() {
        when(valueOps.setIfAbsent(any(), eq("1"), any(Duration.class))).thenReturn(null);

        assertThatThrownBy(() -> service.tryAcquire("test-key", Duration.ofMinutes(5)))
            .isInstanceOf(RedisCacheException.class)
            .hasMessageContaining("null response");
    }

    @Test
    void Redis_장애시_RedisCacheException_전파() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis connection refused"));

        assertThatThrownBy(() -> service.tryAcquire("test-key", Duration.ofMinutes(5)))
            .isInstanceOf(RedisCacheException.class);
    }

    @Test
    void release_성공시_DEL_호출() {
        when(redisTemplate.delete(anyString())).thenReturn(true);

        service.release("test-key");

        verify(redisTemplate).delete("idempotency:test-key");
    }

    @Test
    void release_Redis_실패시_예외_전파_안_함() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("connection refused"));

        // release는 실패해도 예외를 던지지 않아야 함
        assertThatCode(() -> service.release("test-key")).doesNotThrowAnyException();
    }
}
