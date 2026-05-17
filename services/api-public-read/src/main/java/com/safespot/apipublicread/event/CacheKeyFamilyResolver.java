package com.safespot.apipublicread.event;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CacheKeyFamilyResolver {

    public Optional<String> resolve(String cacheKey) {
        if (cacheKey == null) return Optional.empty();
        return switch (cacheKey) {
            case "disaster:messages:list:seoul" -> Optional.of("disaster_messages_list");
            case "disaster:messages:recent:seoul" -> Optional.of("disaster_messages_recent");
            case "disaster:message:core:seoul" -> Optional.of("disaster_message_core");
            case "environment:weather:seoul" -> Optional.of("environment_weather");
            case "environment:air-quality:seoul" -> Optional.of("environment_air_quality");
            case "environment:weather-alert:seoul" -> Optional.of("environment_weather_alert");
            default -> {
                if (cacheKey.startsWith("disaster:detail:")) yield Optional.of("disaster_detail");
                if (cacheKey.startsWith("shelter:status:")) yield Optional.of("shelter_status");
                yield Optional.empty();
            }
        };
    }
}
