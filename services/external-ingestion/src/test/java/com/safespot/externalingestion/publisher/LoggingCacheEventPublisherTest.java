package com.safespot.externalingestion.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.publisher.event.DisasterDataCollectedEvent;
import com.safespot.externalingestion.publisher.impl.LoggingCacheEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingCacheEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publish_sqsDisabled_doesNotThrow() {
        LoggingCacheEventPublisher publisher = new LoggingCacheEventPublisher(objectMapper, false);
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-1", "EARTHQUAKE", "seoul", List.of(1L, 2L), false, "2026-04-21T10:00:00+09:00");

        publisher.publish(event, "disaster-collection");
        // No exception = log-only path completed successfully
    }

    @Test
    void publish_sqsEnabled_throwsToPreventSilentLoss() {
        LoggingCacheEventPublisher publisher = new LoggingCacheEventPublisher(objectMapper, true);
        DisasterDataCollectedEvent event = new DisasterDataCollectedEvent(
            "trace-2", "EARTHQUAKE", "seoul", List.of(3L), false, "2026-04-21T10:01:00+09:00");

        assertThatThrownBy(() -> publisher.publish(event, "disaster-collection"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SQS is enabled but no SQS publisher is configured");
    }
}
