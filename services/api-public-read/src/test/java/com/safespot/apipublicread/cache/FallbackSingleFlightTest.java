package com.safespot.apipublicread.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FallbackSingleFlightTest {

    @Test
    void sameKey_concurrentRequestsShareLeaderResult() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FallbackSingleFlight singleFlight = new FallbackSingleFlight(meterRegistry, 1_000);
        AtomicInteger calls = new AtomicInteger();

        List<String> results = runConcurrent(100, () -> singleFlight.execute(
                "shelter_status",
                "seoul",
                "101",
                () -> {
                    calls.incrementAndGet();
                    sleep(100);
                    return "ok";
                }
        ));

        assertThat(results).hasSize(100).containsOnly("ok");
        assertThat(calls).hasValue(1);
        assertThat(meterRegistry.counter("safespot.fallback.singleflight",
                "service", "api-public-read",
                "cache", "shelter_status",
                "region", "seoul",
                "scope", "local",
                "result", "leader").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("safespot.fallback.singleflight",
                "service", "api-public-read",
                "cache", "shelter_status",
                "region", "seoul",
                "scope", "local",
                "result", "join").count()).isEqualTo(99.0);
    }

    @Test
    void differentKeysRunInParallel() throws Exception {
        FallbackSingleFlight singleFlight = new FallbackSingleFlight(new SimpleMeterRegistry(), 1_000);
        CountDownLatch bothLeadersStarted = new CountDownLatch(2);

        List<String> results = runConcurrent(List.of(
                () -> singleFlight.execute("shelter_status", "seoul", "101",
                        () -> waitForBothLeaders("a", bothLeadersStarted)),
                () -> singleFlight.execute("shelter_status", "seoul", "102",
                        () -> waitForBothLeaders("b", bothLeadersStarted))
        ));

        assertThat(results).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void leaderFailureReleasesFollowersAndCleansUp() throws Exception {
        FallbackSingleFlight singleFlight = new FallbackSingleFlight(new SimpleMeterRegistry(), 1_000);

        assertThatThrownBy(() -> runConcurrent(2, () -> singleFlight.execute(
                "disaster_messages",
                "seoul",
                "list",
                () -> {
                    sleep(100);
                    throw new IllegalStateException("db failed");
                }
        ))).hasRootCauseMessage("db failed");

        assertThat(singleFlight.inFlightSize()).isZero();
    }

    @Test
    void followerTimeoutDoesNotRemoveLeaderEntry() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        FallbackSingleFlight singleFlight = new FallbackSingleFlight(meterRegistry, 50);
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var leader = executor.submit(() -> singleFlight.execute(
                    "disaster_detail",
                    "seoul",
                    "55",
                    () -> {
                        leaderStarted.countDown();
                        await(releaseLeader);
                        return "leader";
                    }
            ));
            assertThat(leaderStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> singleFlight.execute(
                    "disaster_detail",
                    "seoul",
                    "55",
                    () -> "follower"
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Timed out waiting for fallback single-flight");

            assertThat(singleFlight.inFlightSize()).isEqualTo(1);
            assertThat(meterRegistry.counter("safespot.fallback.singleflight",
                    "service", "api-public-read",
                    "cache", "disaster_detail",
                    "region", "seoul",
                    "scope", "local",
                    "result", "timeout").count()).isEqualTo(1.0);

            releaseLeader.countDown();
            assertThat(leader.get(1, TimeUnit.SECONDS)).isEqualTo("leader");
            assertThat(singleFlight.inFlightSize()).isZero();
        } finally {
            releaseLeader.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> List<T> runConcurrent(int count, Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(task);
        }
        return runConcurrent(tasks);
    }

    private static <T> List<T> runConcurrent(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Callable<T>> gated = tasks.stream()
                    .map(task -> (Callable<T>) () -> {
                        start.await();
                        return task.call();
                    })
                    .toList();
            var futures = gated.stream().map(executor::submit).toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(2, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String waitForBothLeaders(String value, CountDownLatch bothLeadersStarted) {
        bothLeadersStarted.countDown();
        await(bothLeadersStarted);
        return value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
