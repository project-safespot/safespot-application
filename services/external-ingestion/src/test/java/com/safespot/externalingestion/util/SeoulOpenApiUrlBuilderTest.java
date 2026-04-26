package com.safespot.externalingestion.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeoulOpenApiUrlBuilderTest {

    @Test
    void replacesKeyPlaceholderInPath() {
        String template = "http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20";
        String result = SeoulOpenApiUrlBuilder.buildUrl(template, "ACTUAL_KEY_VALUE");
        assertThat(result).isEqualTo("http://openapi.seoul.go.kr:8088/ACTUAL_KEY_VALUE/json/TbEqkKenvinfo/1/20");
        assertThat(result).doesNotContain("{KEY}");
    }

    @Test
    void allSeoulApiTemplatesContainPlaceholderAndAreResolvable() {
        // SEOUL_SHELTER_LANDSLIDE는 odcloud로 이전 — 이 목록에서 제외
        List<String> templates = List.of(
            "http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20",
            "http://openapi.seoul.go.kr:8088/{KEY}/json/ListRiverStageService/1/50",
            "http://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/1/1000"
        );
        for (String t : templates) {
            String result = SeoulOpenApiUrlBuilder.buildUrl(t, "TEST_KEY");
            assertThat(result).contains("TEST_KEY");
            assertThat(result).doesNotContain("{KEY}");
            assertThat(result).startsWith("http://openapi.seoul.go.kr:8088/TEST_KEY/json/");
        }
    }

    @Test
    void throwsWhenTemplateHasNoPlaceholder() {
        assertThatThrownBy(() ->
            SeoulOpenApiUrlBuilder.buildUrl("http://openapi.seoul.go.kr:8088/json/TbEqkKenvinfo/1/20", "KEY"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("{KEY}");
    }

    @Test
    void throwsWhenTemplateIsNull() {
        assertThatThrownBy(() -> SeoulOpenApiUrlBuilder.buildUrl(null, "KEY"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyIsNotAddedAsQueryParam() {
        String result = SeoulOpenApiUrlBuilder.buildUrl(
            "http://openapi.seoul.go.kr:8088/{KEY}/json/TbEqkKenvinfo/1/20", "MY_KEY");
        assertThat(result).doesNotContain("?");
        assertThat(result).doesNotContain("KEY=");
    }
}
