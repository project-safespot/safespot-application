package com.safespot.externalingestion.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeoulOpenApiUrlBuilderTest {

    @Test
    void replacesKeyPlaceholder() {
        String url = SeoulOpenApiUrlBuilder.buildUrl(
            "https://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20", "REAL_KEY");
        assertThat(url).isEqualTo(
            "https://openapi.seoul.go.kr:8088/REAL_KEY/json/TbEqkKenvinfo/1/20");
    }

    @Test
    void replacesKeyForRiverLevelTemplate() {
        String url = SeoulOpenApiUrlBuilder.buildUrl(
            "https://openapi.seoul.go.kr:8088/{KEY}/json/ListRiverStageService/1/50", "REAL_KEY");
        assertThat(url).doesNotContain("{KEY}").contains("REAL_KEY");
        assertThat(url).doesNotContain("?");
    }

    @Test
    void replacesKeyForShelterEarthquakeTemplate() {
        String url = SeoulOpenApiUrlBuilder.buildUrl(
            "https://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/1/1000", "REAL_KEY");
        assertThat(url).isEqualTo(
            "https://openapi.seoul.go.kr:8088/REAL_KEY/json/TlEtqkP/1/1000");
    }

    @Test
    void throwsWhenNoPlaceholder() {
        assertThatThrownBy(() ->
            SeoulOpenApiUrlBuilder.buildUrl("https://example.com/api/data", "KEY"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("{KEY}");
    }

    @Test
    void throwsWhenUrlIsNull() {
        assertThatThrownBy(() -> SeoulOpenApiUrlBuilder.buildUrl(null, "KEY"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultContainsNoQueryParamsFromKeySubstitution() {
        String url = SeoulOpenApiUrlBuilder.buildUrl(
            "https://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20", "MYKEY");
        assertThat(url).doesNotContain("?");
        assertThat(url).doesNotContain("KEY=");
    }
}
