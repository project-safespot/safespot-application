package com.safespot.apipublicread.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
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
    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public FallbackSingleFlight(
            MeterRegistry meterRegistry,
            @Value("${safespot.public-read.fallback-singleflight.timeout-ms:2000}") long timeoutMs
    ) {
        this.meterRegistry = meterRegistry;
        this.timeoutMs = timeoutMs;
    }

    public <T> T execute(String cacheKey, String cache, String repository, Supplier<T> supplier) {
        String flightKey = repository + ":" + cacheKey;
        CompletableFuture<Object> leaderFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(flightKey, leaderFuture);

        if (existing == null) {
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
}
