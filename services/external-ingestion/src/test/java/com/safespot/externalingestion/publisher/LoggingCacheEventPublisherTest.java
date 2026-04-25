package com.safespot.externalingestion.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.publisher.impl.LoggingCacheEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

class LoggingCacheEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final LoggingCacheEventPublisher publisher = new LoggingCacheEventPublisher(objectMapper);

    @Test
    void publish_logsWarning_doesNotThrow() {
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-1", "EARTHQUAKE", "seoul", List.of(1L, 2L), false, "2026-04-21T10:00:00+09:00");

        publisher.publish(event, "disaster-collection");
        // No exception = log-only path completed successfully
    }
}
