package com.safespot.asyncworker.service.shelter.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TileCoordinateCalculatorTest {

    @Test
    void 서울시청_좌표의_tile을_계산한다() {
        TileCoordinate tile = TileCoordinateCalculator.from(37.5665, 126.9780, 13);

        assertThat(tile).isEqualTo(new TileCoordinate(13, 6985, 3172));
    }

    @Test
    void 확대수준_16에서도_tile을_계산한다() {
        TileCoordinate tile = TileCoordinateCalculator.from(37.5665, 126.9780, 16);

        assertThat(tile).isEqualTo(new TileCoordinate(16, 55883, 25378));
    }
}
