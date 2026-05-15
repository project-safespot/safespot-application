package com.safespot.apipublicread.event;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class CacheRegenerationRouteResolver {

    private static final Map<String, QueueType> FAMILY_TO_QUEUE = Map.ofEntries(
            Map.entry("shelter_status", QueueType.CACHE_REFRESH),
            Map.entry("shelter_map_item", QueueType.CACHE_REFRESH),
            Map.entry("shelter_geo_index", QueueType.CACHE_REFRESH),
            Map.entry("shelter_map_tile", QueueType.CACHE_REFRESH),
            Map.entry("disaster_messages_list", QueueType.READMODEL_REFRESH),
            Map.entry("disaster_messages_recent", QueueType.READMODEL_REFRESH),
            Map.entry("disaster_message_core", QueueType.READMODEL_REFRESH),
            Map.entry("disaster_detail", QueueType.READMODEL_REFRESH),
            Map.entry("environment_weather", QueueType.ENVIRONMENT_CACHE_REFRESH),
            Map.entry("environment_air_quality", QueueType.ENVIRONMENT_CACHE_REFRESH),
            Map.entry("environment_weather_alert", QueueType.ENVIRONMENT_CACHE_REFRESH)
    );

    public Optional<CacheRegenerationRoute> resolve(String cacheFamily) {
        if (cacheFamily == null) return Optional.empty();
        QueueType queueType = FAMILY_TO_QUEUE.get(cacheFamily);
        if (queueType == null) return Optional.empty();
        return Optional.of(new CacheRegenerationRoute(queueType));
    }
}
