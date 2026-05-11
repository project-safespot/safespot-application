package com.safespot.asyncworker.config;

import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.handler.CacheRegenerationAsyncWorkerHandler;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.handler.cache.EnvironmentDataCollectedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryCreatedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryExitedHandler;
import com.safespot.asyncworker.handler.cache.EvacuationEntryUpdatedHandler;
import com.safespot.asyncworker.handler.cache.ShelterStatusWarmupRequestedHandler;
import com.safespot.asyncworker.handler.cache.ShelterUpdatedHandler;
import com.safespot.asyncworker.handler.readmodel.DisasterDataCollectedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Profile("async-worker")
@Configuration
public class AsyncWorkerConfig {

    @Bean
    public EventDispatcher asyncWorkerDispatcher(
        EvacuationEntryCreatedHandler evacuationCreatedHandler,
        EvacuationEntryExitedHandler evacuationExitedHandler,
        EvacuationEntryUpdatedHandler evacuationUpdatedHandler,
        ShelterUpdatedHandler shelterUpdatedHandler,
        EnvironmentDataCollectedHandler environmentHandler,
        DisasterDataCollectedHandler disasterHandler,
        CacheRegenerationAsyncWorkerHandler cacheRegenerationHandler,
        ShelterStatusWarmupRequestedHandler shelterStatusWarmupHandler
    ) {
        List<EventHandler> handlers = List.of(
            evacuationCreatedHandler,
            evacuationExitedHandler,
            evacuationUpdatedHandler,
            shelterUpdatedHandler,
            environmentHandler,
            disasterHandler,
            cacheRegenerationHandler,
            shelterStatusWarmupHandler
        );
        return new EventDispatcher(handlers);
    }
}
