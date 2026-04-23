package com.safespot.apipublicread.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressWindowServiceTest {

    private SuppressWindowService suppressWindowService;

    @BeforeEach
    void setUp() {
        suppressWindowService = new SuppressWindowService();
    }

    @Test
    void firstCallShouldPublish() {
        assertThat(suppressWindowService.shouldPublish("shelter:status:1")).isTrue();
    }

    @Test
    void tryPublishReturnsTrueAndMarks() {
        assertThat(suppressWindowService.tryPublish("shelter:status:1")).isTrue();
    }

    @Test
    void secondCallWithinWindowShouldNotPublish() {
        suppressWindowService.tryPublish("shelter:status:1");
        assertThat(suppressWindowService.shouldPublish("shelter:status:1")).isFalse();
    }

    @Test
    void differentKeysShouldBeIndependent() {
        suppressWindowService.tryPublish("shelter:status:1");

        assertThat(suppressWindowService.shouldPublish("shelter:status:2")).isTrue();
    }

    @Test
    void tryPublishSecondTimeWithinWindowReturnsFalse() {
        suppressWindowService.tryPublish("shelter:status:1");
        assertThat(suppressWindowService.tryPublish("shelter:status:1")).isFalse();
    }

    @Test
    void tryPublish_concurrent_onlyOneSucceeds() throws InterruptedException {
        int threads = 20;
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                if (suppressWindowService.tryPublish("shelter:status:concurrent")) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
    }
}
