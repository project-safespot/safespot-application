package com.safespot.apipublicread.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    private final PublicReadMetricRecorder metricRecorder;
    private final long timeoutMs;
    private final ConcurrentMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public FallbackSingleFlight(
            PublicReadMetricRecorder metricRecorder,
            @Value("${safespot.public-read.fallback-singleflight.timeout-ms:2000}") long timeoutMs
    ) {
        this.metricRecorder = metricRecorder;
        this.timeoutMs = timeoutMs;
    }

    public FallbackSingleFlight(io.micrometer.core.instrument.MeterRegistry meterRegistry, long timeoutMs) {
        this(new PublicReadMetricRecorder(meterRegistry), timeoutMs);
    }

    public <T> T execute(String cache, String region, String logicalKey, Supplier<T> supplier) {
        String flightKey = "fallback:%s:%s:%s".formatted(cache, region, logicalKey);
        CompletableFuture<Object> leaderFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(flightKey, leaderFuture);

        if (existing == null) {
            metricRecorder.recordFallbackSingleflight(cache, "local", "leader");
            try {
                T result = supplier.get();
                leaderFuture.complete(result);
                return result;
            } catch (Throwable t) {
                metricRecorder.recordFallbackSingleflight(cache, "local", "error");
                leaderFuture.completeExceptionally(t);
                throw propagate(t);
            } finally {
                inFlight.remove(flightKey, leaderFuture);
            }
        }

        metricRecorder.recordFallbackSingleflight(cache, "local", "join");
        try {
            @SuppressWarnings("unchecked")
            T result = (T) existing.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result;
        } catch (TimeoutException e) {
            metricRecorder.recordFallbackSingleflight(cache, "local", "timeout");
            throw new JoinTimeoutException(flightKey, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for fallback single-flight key=" + flightKey, e);
        } catch (ExecutionException e) {
            throw propagate(e.getCause());
        }
    }

    int inFlightSize() {
        return inFlight.size();
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

}
