package com.safespot.asyncworker.config;

import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.handler.cache.CacheRegenerationCacheWorkerHandler;
import com.safespot.asyncworker.handler.cache.EnvironmentDataCollectedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryCreatedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryExitedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryUpdatedHandler;
import com.safespot.asyncworker.handler.cache.ShelterUpdatedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Profile("cache-worker")
@Configuration
public class CacheWorkerConfig {

    @Bean
    public EventDispatcher cacheWorkerDispatcher(
        EvacuationEntryCreatedHandler createdHandler,
        EvacuationEntryExitedHandler exitedHandler,
        EvacuationEntryUpdatedHandler updatedHandler,
        ShelterUpdatedHandler shelterUpdatedHandler,
        EnvironmentDataCollectedHandler environmentHandler,
        CacheRegenerationCacheWorkerHandler cacheRegenerationHandler
    ) {
        List<EventHandler> handlers = List.of(
            createdHandler,
            exitedHandler,
            updatedHandler,
            shelterUpdatedHandler,
            environmentHandler,
            cacheRegenerationHandler
        );
        return new EventDispatcher(handlers);
    }
}
