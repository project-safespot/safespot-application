package com.safespot.apipublicread.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuppressWindowServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private SuppressWindowService suppressWindowService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        suppressWindowService = new SuppressWindowService(redisTemplate);
    }

    @Test
    void tryPublish_setNxSucceeds_returnsTrue() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(30)))).thenReturn(true);

        assertThat(suppressWindowService.tryPublish("disaster:messages:list:seoul")).isTrue();
    }

    @Test
    void tryPublish_setNxFails_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(30)))).thenReturn(false);

        assertThat(suppressWindowService.tryPublish("disaster:messages:list:seoul")).isFalse();
    }

    @Test
    void tryPublish_setNxReturnsNull_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(30)))).thenReturn(null);

        assertThat(suppressWindowService.tryPublish("disaster:messages:list:seoul")).isFalse();
    }

    @Test
    void tryPublish_redisDown_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(suppressWindowService.tryPublish("disaster:messages:list:seoul")).isFalse();
    }

    @Test
    void tryPublish_differentKeys_useDifferentSuppressKeys() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        suppressWindowService.tryPublish("disaster:messages:list:seoul");
        suppressWindowService.tryPublish("disaster:detail:55");

        // Both calls must use different suppress keys (captured via ArgumentCaptor)
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).setIfAbsent(captor.capture(), anyString(), any(Duration.class));
        assertThat(captor.getAllValues().get(0)).isNotEqualTo(captor.getAllValues().get(1));
    }

    @Test
    void tryPublish_suppressKeyStartsWithPrefix() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        suppressWindowService.tryPublish("disaster:messages:list:seoul");

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(captor.capture(), anyString(), any(Duration.class));
        assertThat(captor.getValue()).startsWith("suppress:cache-regeneration:");
    }
}
