package com.safespot.apipublicread.cache;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegionToGridResolverTest {

    private final RegionToGridResolver resolver = new RegionToGridResolver();

    @Test
    void resolve_seoulSpecialCity_returnsGrid() {
        Optional<int[]> result = resolver.resolve("서울특별시");
        assertThat(result).isPresent();
        assertThat(result.get()[0]).isEqualTo(60);
        assertThat(result.get()[1]).isEqualTo(127);
    }

    @Test
    void resolve_seoulShorthand_returnsGrid() {
        assertThat(resolver.resolve("서울")).isPresent();
    }

    @Test
    void resolve_seoulGu_returnsGrid() {
        assertThat(resolver.resolve("강남구")).isPresent();
        assertThat(resolver.resolve("종로구")).isPresent();
        assertThat(resolver.resolve("강서구")).isPresent();
    }

    @Test
    void resolve_unsupportedRegion_returnsEmpty() {
        assertThat(resolver.resolve("부산광역시")).isEmpty();
        assertThat(resolver.resolve("대구광역시")).isEmpty();
        assertThat(resolver.resolve("경기도")).isEmpty();
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void isSupported_seoulRegions_returnsTrue() {
        assertThat(resolver.isSupported("서울특별시")).isTrue();
        assertThat(resolver.isSupported("서초구")).isTrue();
    }

    @Test
    void isSupported_nonSeoulRegions_returnsFalse() {
        assertThat(resolver.isSupported("부산")).isFalse();
        assertThat(resolver.isSupported("제주")).isFalse();
    }
}
