package com.safespot.apipublicread.event;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class CacheRegenerationRouteResolver {

    private static final Map<String, QueueType> FAMILY_TO_QUEUE = Map.of(
            "shelter_status", QueueType.CACHE_REFRESH,
            "disaster_messages_list", QueueType.READMODEL_REFRESH,
            "disaster_messages_recent", QueueType.READMODEL_REFRESH,
            "disaster_message_core", QueueType.READMODEL_REFRESH,
            "disaster_detail", QueueType.READMODEL_REFRESH,
            "environment_weather", QueueType.ENVIRONMENT_CACHE_REFRESH,
            "environment_air_quality", QueueType.ENVIRONMENT_CACHE_REFRESH,
            "environment_weather_alert", QueueType.ENVIRONMENT_CACHE_REFRESH
    );

    public Optional<CacheRegenerationRoute> resolve(String cacheFamily) {
        if (cacheFamily == null) return Optional.empty();
        QueueType queueType = FAMILY_TO_QUEUE.get(cacheFamily);
        if (queueType == null) return Optional.empty();
        return Optional.of(new CacheRegenerationRoute(queueType));
    }
}
