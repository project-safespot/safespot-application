package com.safespot.asyncworker.redis;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisTtlConstantsTest {

    @RepeatedTest(200)
    void withJitter_10percent_producesValuesWithinRange() {
        Duration base = Duration.ofSeconds(600);
        Duration result = RedisTtlConstants.withJitter(base, 0.1);

        assertThat(result.toMillis())
                .isGreaterThanOrEqualTo(540_000L)
                .isLessThan(660_000L);
    }

    @Test
    void withJitter_neverFallsBelowMinimum() {
        Duration tiny = Duration.ofMillis(10);
        for (int i = 0; i < 100; i++) {
            Duration result = RedisTtlConstants.withJitter(tiny, 0.5);
            assertThat(result.toMillis())
                    .isGreaterThanOrEqualTo(1_000L);
        }
    }

    @RepeatedTest(50)
    void withJitter_notAlwaysSameValue() {
        // 200번 중 적어도 값이 분산되는지 확인 — 고정값이 아님을 검증
        Duration base = Duration.ofSeconds(600);
        Duration r1 = RedisTtlConstants.withJitter(base, 0.1);
        Duration r2 = RedisTtlConstants.withJitter(base, 0.1);
        // 두 값이 반드시 다를 수는 없지만(확률 극히 낮음), 둘 다 범위 안에 있어야 함
        assertThat(r1.toMillis()).isBetween(540_000L, 659_999L);
        assertThat(r2.toMillis()).isBetween(540_000L, 659_999L);
    }

    @Test
    void shelterStatus_baseTtl_is600s() {
        assertThat(RedisTtlConstants.SHELTER_STATUS.getSeconds()).isEqualTo(600L);
    }

    @RepeatedTest(200)
    void withJitter_shelterStatus_10percent_producesValuesWithinRange() {
        Duration result = RedisTtlConstants.withJitter(RedisTtlConstants.SHELTER_STATUS, 0.1);

        assertThat(result.toMillis())
                .isGreaterThanOrEqualTo(540_000L)
                .isLessThan(660_000L);
    }

    @Test
    void disasterMessages_baseTtl_is600s() {
        assertThat(RedisTtlConstants.DISASTER_MESSAGES_LIST.getSeconds()).isEqualTo(600L);
        assertThat(RedisTtlConstants.DISASTER_MESSAGES_RECENT.getSeconds()).isEqualTo(600L);
        assertThat(RedisTtlConstants.DISASTER_MESSAGE_CORE.getSeconds()).isEqualTo(600L);
    }

    @Test
    void disasterDetail_baseTtl_unchanged_3600s() {
        assertThat(RedisTtlConstants.DISASTER_DETAIL.getSeconds()).isEqualTo(3600L);
    }
}
