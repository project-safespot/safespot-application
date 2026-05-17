package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyFamilyResolverTest {

    private final CacheKeyFamilyResolver resolver = new CacheKeyFamilyResolver();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "disaster:messages:list:seoul, disaster_messages_list",
            "disaster:detail:42, disaster_detail",
            "disaster:detail:999999, disaster_detail",
            "shelter:detail:101, shelter_detail",
            "shelter:status:1, shelter_status",
            "shelter:status:101, shelter_status",
            "environment:weather:seoul, environment_weather",
            "environment:air-quality:seoul, environment_air_quality",
    })
    void resolve_activeMappings_returnsExpectedFamily(String cacheKey, String expectedFamily) {
        Optional<String> result = resolver.resolve(cacheKey);
        assertThat(result).isPresent().contains(expectedFamily);
    }

    @ParameterizedTest(name = "inactive: {0} -> {1}")
    @CsvSource({
            "disaster:messages:recent:seoul, disaster_messages_recent",
            "disaster:message:core:seoul, disaster_message_core",
            "environment:weather-alert:seoul, environment_weather_alert",
    })
    void resolve_inactiveMappings_returnsExpectedFamily(String cacheKey, String expectedFamily) {
        Optional<String> result = resolver.resolve(cacheKey);
        assertThat(result).isPresent().contains(expectedFamily);
    }

    @ParameterizedTest(name = "unsupported: {0}")
    @CsvSource({
            "unknown:key",
            "disaster:messages:list:busan",
            "shelter:list:seoul:page1",
            "environment:weather:busan",
    })
    void resolve_unsupportedKey_returnsEmpty(String cacheKey) {
        assertThat(resolver.resolve(cacheKey)).isEmpty();
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
