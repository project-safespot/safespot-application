package com.safespot.asyncworker.config;

import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.handler.cache.EnvironmentDataCollectedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryCreatedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryExitedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryUpdatedHandler;
import com.safespot.asyncworker.handler.cache.ShelterUpdatedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class CacheWorkerConfig {

    @Primary
    @Bean
    public EventDispatcher cacheWorkerDispatcher(
        EvacuationEntryCreatedHandler createdHandler,
        EvacuationEntryExitedHandler exitedHandler,
        EvacuationEntryUpdatedHandler updatedHandler,
        ShelterUpdatedHandler shelterUpdatedHandler,
        EnvironmentDataCollectedHandler environmentHandler
    ) {
        List<EventHandler> handlers = List.of(
            createdHandler,
            exitedHandler,
            updatedHandler,
            shelterUpdatedHandler,
            environmentHandler
        );
        return new EventDispatcher(handlers);
    }
}
