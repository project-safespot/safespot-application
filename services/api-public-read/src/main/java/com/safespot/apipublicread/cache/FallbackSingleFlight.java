package com.safespot.apipublicread.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class FallbackSingleFlight {

    public static class JoinTimeoutException extends IllegalStateException {
        public JoinTimeoutException(String cacheKey, Throwable cause) {
            super("Timed out waiting for fallback single-flight key=" + cacheKey, cause);
        }
    }

    private final MeterRegistry meterRegistry;
    private final long timeoutMs;
    private final int memoMaxSize;
    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MemoizedValue> memoized = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightGauge;
    private final AtomicInteger memoSizeGauge;

    public FallbackSingleFlight(
            MeterRegistry meterRegistry,
            @Value("${safespot.public-read.fallback-singleflight.timeout-ms:2000}") long timeoutMs,
            @Value("${safespot.public-read.fallback-singleflight.memo-max-size:10000}") int memoMaxSize
    ) {
        this.meterRegistry = meterRegistry;
        this.timeoutMs = timeoutMs;
        this.memoMaxSize = memoMaxSize;
        this.inFlightGauge = meterRegistry.gauge("fallback_singleflight_inflight_gauge",
                new AtomicInteger(inFlight.size()));
        this.memoSizeGauge = meterRegistry.gauge("fallback_singleflight_memo_size_gauge",
                new AtomicInteger(memoized.size()));
    }

    FallbackSingleFlight(MeterRegistry meterRegistry, long timeoutMs) {
        this(meterRegistry, timeoutMs, 10_000);
    }

    public <T> T execute(String cacheKey, String cache, String repository, Supplier<T> supplier) {
        String flightKey = repository + ":" + cacheKey;
        CompletableFuture<Object> leaderFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(flightKey, leaderFuture);

        if (existing == null) {
            updateInFlightGauge();
            record("fallback_singleflight_leader_total", cache, repository, "leader");
            try {
                T result = supplier.get();
                leaderFuture.complete(result);
                return result;
            } catch (Throwable t) {
                leaderFuture.completeExceptionally(t);
                throw propagate(t);
            } finally {
                inFlight.remove(flightKey, leaderFuture);
                updateInFlightGauge();
            }
        }

        record("fallback_singleflight_join_total", cache, repository, "join");
        record("fallback_suppressed_total", cache, repository, "singleflight_join");
        try {
            @SuppressWarnings("unchecked")
            T result = (T) existing.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            record("fallback_singleflight_timeout_total", cache, repository, "timeout");
            throw new JoinTimeoutException(cacheKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for fallback single-flight key=" + cacheKey, e);
        } catch (ExecutionException e) {
            throw propagate(e.getCause());
        }
    }

    public <T> T executeMemoized(
            String cacheKey,
            String cache,
            String repository,
            Duration memoTtl,
            Supplier<T> supplier
    ) {
        String flightKey = repository + ":" + cacheKey;
        T memoHit = getMemoizedValue(flightKey, cache, repository);
        if (memoHit != null) {
            return memoHit;
        }

        CompletableFuture<Object> leaderFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(flightKey, leaderFuture);

        if (existing == null) {
            updateInFlightGauge();
            record("fallback_singleflight_leader_total", cache, repository, "leader");
            try {
                T result = supplier.get();
                leaderFuture.complete(result);
                storeMemoizedValue(flightKey, result, memoTtl, cache, repository);
                return result;
            } catch (Throwable t) {
                leaderFuture.completeExceptionally(t);
                throw propagate(t);
            } finally {
                inFlight.remove(flightKey, leaderFuture);
                updateInFlightGauge();
            }
        }

        record("fallback_singleflight_join_total", cache, repository, "join");
        record("fallback_suppressed_total", cache, repository, "singleflight_join");
        try {
            @SuppressWarnings("unchecked")
            T result = (T) existing.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            record("fallback_singleflight_timeout_total", cache, repository, "timeout");
            throw new JoinTimeoutException(cacheKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for fallback single-flight key=" + cacheKey, e);
        } catch (ExecutionException e) {
            throw propagate(e.getCause());
        }
    }

    int inFlightSize() {
        return inFlight.size();
    }

    int memoizedSize() {
        return memoized.size();
    }

    private void record(String metricName, String cache, String repository, String result) {
        meterRegistry.counter(metricName,
                "service", "api-public-read",
                "cache", lowCardinality(cache),
                "repository", lowCardinality(repository),
                "result", result
        ).increment();
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(t);
    }

    private static String lowCardinality(String value) {
        return Optional.ofNullable(value)
                .filter(v -> v.matches("[a-zA-Z0-9_./{}-]+"))
                .orElse("unknown");
    }

    private <T> T getMemoizedValue(String flightKey, String cache, String repository) {
        MemoizedValue memoizedValue = memoized.get(flightKey);
        if (memoizedValue == null) {
            return null;
        }
        if (memoizedValue.isExpired()) {
            if (memoized.remove(flightKey, memoizedValue)) {
                record("fallback_singleflight_memo_expired_total", cache, repository, "expired");
                updateMemoSizeGauge();
            }
            return null;
        }
        record("fallback_singleflight_memo_hit_total", cache, repository, "memo_hit");
        @SuppressWarnings("unchecked")
        T value = (T) memoizedValue.value();
        return value;
    }

    private void storeMemoizedValue(String flightKey, Object value, Duration memoTtl, String cache, String repository) {
        if (memoTtl == null || memoTtl.isZero() || memoTtl.isNegative()) {
            return;
        }
        cleanupExpiredMemoEntries(cache, repository);
        if (memoized.size() >= memoMaxSize) {
            return;
        }
        memoized.put(flightKey, MemoizedValue.of(value, memoTtl));
        record("fallback_singleflight_memo_store_total", cache, repository, "memo_store");
        updateMemoSizeGauge();
    }

    private void cleanupExpiredMemoEntries(String cache, String repository) {
        memoized.forEach((key, memoizedValue) -> {
            if (memoizedValue.isExpired() && memoized.remove(key, memoizedValue)) {
                record("fallback_singleflight_memo_expired_total", cache, repository, "expired");
            }
        });
        updateMemoSizeGauge();
    }

    private void updateInFlightGauge() {
        inFlightGauge.set(inFlight.size());
    }

    private void updateMemoSizeGauge() {
        memoSizeGauge.set(memoized.size());
    }

    private record MemoizedValue(Object value, long expiresAtNanos) {
        private static MemoizedValue of(Object value, Duration ttl) {
            return new MemoizedValue(value, System.nanoTime() + ttl.toNanos());
        }

        private boolean isExpired() {
            return System.nanoTime() >= expiresAtNanos;
        }
    }
}
