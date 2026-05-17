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

    @RepeatedTest(200)
    void withAddedJitter_shelter_disaster_producesValuesInRange_3600to3720s() {
        Duration result = RedisTtlConstants.withAddedJitter(
                RedisTtlConstants.SHELTER_STATUS, RedisTtlConstants.SHELTER_DISASTER_JITTER);

        assertThat(result.toMillis())
                .isGreaterThanOrEqualTo(3_600_000L)
                .isLessThanOrEqualTo(3_720_000L);
    }

    @RepeatedTest(200)
    void withAddedJitter_neverBelowBase() {
        Duration base = Duration.ofSeconds(3600);
        Duration jitter = Duration.ofSeconds(120);
        Duration result = RedisTtlConstants.withAddedJitter(base, jitter);

        assertThat(result.toMillis()).isGreaterThanOrEqualTo(base.toMillis());
    }

    @Test
    void shelterStatus_baseTtl_is3600s() {
        assertThat(RedisTtlConstants.SHELTER_STATUS.getSeconds()).isEqualTo(3600L);
    }

    @Test
    void shelterMapItem_baseTtl_is3600s() {
        assertThat(RedisTtlConstants.SHELTER_MAP_ITEM.getSeconds()).isEqualTo(3600L);
    }

    @Test
    void shelterMapTile_baseTtl_is600s() {
        assertThat(RedisTtlConstants.SHELTER_MAP_TILE.getSeconds()).isEqualTo(600L);
    }

    @Test
    void shelterDisasterJitter_is120s() {
        assertThat(RedisTtlConstants.SHELTER_DISASTER_JITTER.getSeconds()).isEqualTo(120L);
    }

    @RepeatedTest(200)
    void withAddedJitter_shelter_map_item_producesValuesInRange_3600to3720s() {
        Duration result = RedisTtlConstants.withAddedJitter(
            RedisTtlConstants.SHELTER_MAP_ITEM, RedisTtlConstants.SHELTER_DISASTER_JITTER);

        assertThat(result.toMillis())
            .isGreaterThanOrEqualTo(3_600_000L)
            .isLessThanOrEqualTo(3_720_000L);
    }

    @Test
    void disasterMessages_baseTtl_is3600s() {
        assertThat(RedisTtlConstants.DISASTER_MESSAGES_LIST.getSeconds()).isEqualTo(3600L);
        assertThat(RedisTtlConstants.DISASTER_MESSAGES_RECENT.getSeconds()).isEqualTo(3600L);
        assertThat(RedisTtlConstants.DISASTER_MESSAGE_CORE.getSeconds()).isEqualTo(3600L);
    }

    @Test
    void disasterDetail_baseTtl_is3600s() {
        assertThat(RedisTtlConstants.DISASTER_DETAIL.getSeconds()).isEqualTo(3600L);
    }
}
