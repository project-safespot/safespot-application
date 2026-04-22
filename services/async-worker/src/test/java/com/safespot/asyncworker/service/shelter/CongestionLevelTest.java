package com.safespot.asyncworker.service.shelter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CongestionLevelTest {

    @Test
    void 점유율_0퍼센트_AVAILABLE() {
        assertThat(CongestionLevel.of(0, 100)).isEqualTo(CongestionLevel.AVAILABLE);
    }

    @Test
    void 점유율_49퍼센트_AVAILABLE() {
        assertThat(CongestionLevel.of(49, 100)).isEqualTo(CongestionLevel.AVAILABLE);
    }

    @Test
    void 점유율_50퍼센트_NORMAL() {
        assertThat(CongestionLevel.of(50, 100)).isEqualTo(CongestionLevel.NORMAL);
    }

    @Test
    void 점유율_74퍼센트_NORMAL() {
        assertThat(CongestionLevel.of(74, 100)).isEqualTo(CongestionLevel.NORMAL);
    }

    @Test
    void 점유율_75퍼센트_CROWDED() {
        assertThat(CongestionLevel.of(75, 100)).isEqualTo(CongestionLevel.CROWDED);
    }

    @Test
    void 점유율_99퍼센트_CROWDED() {
        assertThat(CongestionLevel.of(99, 100)).isEqualTo(CongestionLevel.CROWDED);
    }

    @Test
    void 점유율_100퍼센트_FULL() {
        assertThat(CongestionLevel.of(100, 100)).isEqualTo(CongestionLevel.FULL);
    }

    @Test
    void 점유율_초과_FULL() {
        assertThat(CongestionLevel.of(120, 100)).isEqualTo(CongestionLevel.FULL);
    }

    @Test
    void 수용인원_0_FULL() {
        assertThat(CongestionLevel.of(0, 0)).isEqualTo(CongestionLevel.FULL);
    }
}
