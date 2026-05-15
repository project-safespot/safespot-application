package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRegenerationRouteResolverTest {

    private final CacheRegenerationRouteResolver resolver = new CacheRegenerationRouteResolver();

    @ParameterizedTest(name = "{0} -> READMODEL_REFRESH")
    @CsvSource({
            "disaster_detail",
            "disaster_messages_list",
            "disaster_messages_recent",
            "disaster_message_core",
    })
    void resolve_disasterFamily_returnsReadmodelRefreshRoute(String family) {
        assertThat(resolver.resolve(family))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.queueType()).isEqualTo(QueueType.READMODEL_REFRESH));
    }

    @Test
    void resolve_shelterStatus_returnsCacheRefreshRoute() {
        assertThat(resolver.resolve("shelter_status"))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.queueType()).isEqualTo(QueueType.CACHE_REFRESH));
    }

    @ParameterizedTest(name = "{0} -> CACHE_REFRESH")
    @CsvSource({
            "shelter_map_item",
            "shelter_geo_index",
            "shelter_map_tile",
    })
    void resolve_shelterMapFamily_returnsCacheRefreshRoute(String family) {
        assertThat(resolver.resolve(family))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.queueType()).isEqualTo(QueueType.CACHE_REFRESH));
    }

    @ParameterizedTest(name = "{0} -> ENVIRONMENT_CACHE_REFRESH")
    @CsvSource({
            "environment_weather",
            "environment_air_quality",
            "environment_weather_alert",
    })
    void resolve_environmentFamily_returnsEnvironmentCacheRefreshRoute(String family) {
        assertThat(resolver.resolve(family))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.queueType()).isEqualTo(QueueType.ENVIRONMENT_CACHE_REFRESH));
    }

    @ParameterizedTest(name = "unsupported: {0}")
    @CsvSource({
            "unknown_family",
            "shelter_list",
    })
    void resolve_unsupportedFamily_returnsEmpty(String family) {
        assertThat(resolver.resolve(family)).isEmpty();
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void resolve_route_envelopeTypeMatchesQueueTypeLabel() {
        CacheRegenerationRoute route = resolver.resolve("disaster_detail").orElseThrow();
        assertThat(route.envelopeType()).isEqualTo("readmodel-refresh");
    }

    @Test
    void resolve_cacheRefreshRoute_envelopeTypeLabel() {
        CacheRegenerationRoute route = resolver.resolve("shelter_status").orElseThrow();
        assertThat(route.envelopeType()).isEqualTo("cache-refresh");
    }

    @Test
    void resolve_environmentCacheRefreshRoute_envelopeTypeLabel() {
        CacheRegenerationRoute route = resolver.resolve("environment_weather").orElseThrow();
        assertThat(route.envelopeType()).isEqualTo("environment-cache-refresh");
    }
}
