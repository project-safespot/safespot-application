package com.safespot.externalingestion.publisher;

import com.safespot.externalingestion.publisher.event.IngestionEvent;

public interface CacheEventPublisher {
    void publish(IngestionEvent event, String logicalQueueName);
}
