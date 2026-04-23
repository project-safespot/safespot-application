package com.safespot.externalingestion.publisher;

import com.safespot.externalingestion.publisher.event.CacheRefreshEvent;

public interface CacheEventPublisher {
    void publish(CacheRefreshEvent event, String queueTarget);
}
