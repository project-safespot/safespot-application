package com.safespot.asyncworker.config;

import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.handler.readmodel.CacheRegenerationReadModelWorkerHandler;
import com.safespot.asyncworker.handler.readmodel.DisasterDataCollectedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Profile("readmodel-worker")
@Configuration
public class ReadModelWorkerConfig {

    @Bean
    public EventDispatcher readModelWorkerDispatcher(
        DisasterDataCollectedHandler disasterHandler,
        CacheRegenerationReadModelWorkerHandler cacheRegenerationHandler
    ) {
        List<EventHandler> handlers = List.of(disasterHandler, cacheRegenerationHandler);
        return new EventDispatcher(handlers);
    }
}
