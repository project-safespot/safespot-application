package com.safespot.apipublicread.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CongestionCalculatorTest {

    @Test
    void available_when_under50Percent() {
        assertThat(CongestionCalculator.calculate(100, 49)).isEqualTo("AVAILABLE");
        assertThat(CongestionCalculator.calculate(100, 0)).isEqualTo("AVAILABLE");
    }

    @Test
    void normal_when_50to74Percent() {
        assertThat(CongestionCalculator.calculate(100, 50)).isEqualTo("NORMAL");
        assertThat(CongestionCalculator.calculate(100, 74)).isEqualTo("NORMAL");
    }

    @Test
    void crowded_when_75to99Percent() {
        assertThat(CongestionCalculator.calculate(100, 75)).isEqualTo("CROWDED");
        assertThat(CongestionCalculator.calculate(100, 99)).isEqualTo("CROWDED");
    }

    @Test
    void full_when_100Percent() {
        assertThat(CongestionCalculator.calculate(100, 100)).isEqualTo("FULL");
        assertThat(CongestionCalculator.calculate(100, 101)).isEqualTo("FULL");
    }

    @Test
    void zeroCapacityShouldReturnAvailable() {
        assertThat(CongestionCalculator.calculate(0, 0)).isEqualTo("AVAILABLE");
    }

    @Test
    void distanceCalculation() {
        // 서울 시청 → 광화문 (약 600m)
        int dist = CongestionCalculator.distanceMeters(37.5663, 126.9779, 37.5717, 126.9769);
        assertThat(dist).isBetween(500, 700);
    }
}
