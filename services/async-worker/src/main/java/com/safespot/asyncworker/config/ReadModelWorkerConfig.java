package com.safespot.asyncworker.config;

import com.safespot.asyncworker.dispatcher.EventDispatcher;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.handler.readmodel.DisasterDataCollectedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ReadModelWorkerConfig {

    @Bean
    public EventDispatcher readModelWorkerDispatcher(DisasterDataCollectedHandler disasterHandler) {
        List<EventHandler> handlers = List.of(disasterHandler);
        return new EventDispatcher(handlers);
    }
}
