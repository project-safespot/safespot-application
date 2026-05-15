package com.safespot.apipublicread.event;

import java.util.List;

public interface CacheRegenerationPublisher {
    void publish(String cacheKey, CacheRegenerationReason reason, String endpoint);

    void publishBatch(String targetType, List<Long> targetIds, CacheRegenerationReason reason, String endpoint);

    void publishTarget(String targetType, CacheRegenerationReason reason, String endpoint);
}
