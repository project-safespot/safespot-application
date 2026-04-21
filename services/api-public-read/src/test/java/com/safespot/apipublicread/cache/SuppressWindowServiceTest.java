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
}
