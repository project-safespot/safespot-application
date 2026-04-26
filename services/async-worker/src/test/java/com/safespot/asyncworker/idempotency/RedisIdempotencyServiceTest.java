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
import static org.mockito.Mockito.doThrow;
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

    // ── tryAcquire ─────────────────────────────────────────────────────────────

    @Test
    void SETNX_성공시_PROCESSING_세팅_후_true_반환() {
        when(valueOps.setIfAbsent(any(), eq(RedisIdempotencyService.PROCESSING), any(Duration.class))).thenReturn(true);

        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void COMPLETED_키_있을때_tryAcquire_false_반환() {
        when(valueOps.setIfAbsent(any(), eq(RedisIdempotencyService.PROCESSING), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(RedisIdempotencyService.COMPLETED);

        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void PROCESSING_키_있을때_tryAcquire_true_반환_이전_실패_재시도_허용() {
        when(valueOps.setIfAbsent(any(), eq(RedisIdempotencyService.PROCESSING), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(RedisIdempotencyService.PROCESSING);

        // PROCESSING 상태 = 이전 처리 실패 → 재시도 허용 → true 반환
        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void PROCESSING_후_GET_null_레이스_컨디션시_true_반환() {
        when(valueOps.setIfAbsent(any(), eq(RedisIdempotencyService.PROCESSING), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(service.tryAcquire("test-key", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void SETNX_null_반환시_RedisCacheException_전파() {
        when(valueOps.setIfAbsent(any(), eq(RedisIdempotencyService.PROCESSING), any(Duration.class))).thenReturn(null);

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

    // ── markCompleted ──────────────────────────────────────────────────────────

    @Test
    void markCompleted_성공시_COMPLETED_SET() {
        service.markCompleted("test-key", Duration.ofMinutes(5));

        verify(valueOps).set(anyString(), eq(RedisIdempotencyService.COMPLETED), any(Duration.class));
    }

    @Test
    void markCompleted_실패시_예외_전파_안_함() {
        doThrow(new RuntimeException("Redis error")).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> service.markCompleted("test-key", Duration.ofMinutes(5))).doesNotThrowAnyException();
    }

    // ── release ────────────────────────────────────────────────────────────────

    @Test
    void release_성공시_DEL_호출() {
        when(redisTemplate.delete(anyString())).thenReturn(true);

        service.release("test-key");

        verify(redisTemplate).delete("idempotency:test-key");
    }

    @Test
    void release_Redis_실패시_예외_전파_안_함_키는_PROCESSING으로_남아_재시도_보장() {
        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("connection refused"));

        // release 실패 시 예외를 던지지 않는다
        // 키가 PROCESSING으로 남으면 다음 tryAcquire가 true를 반환해 재시도가 정상 진행됨
        assertThatCode(() -> service.release("test-key")).doesNotThrowAnyException();
    }
}
