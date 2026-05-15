package com.safespot.apipublicread.event;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CacheRegenerationEnvelopeFactory {

    public CacheRegenerationEnvelope build(QueueType queueType, String cacheKey,
                                            String cacheFamily, CacheRegenerationReason reason) {
        return switch (queueType) {
            case CACHE_REFRESH -> CacheRegenerationEnvelope.build(cacheKey, cacheFamily, reason);
            case READMODEL_REFRESH -> CacheRegenerationEnvelope.build(cacheKey, cacheFamily, reason);
            case ENVIRONMENT_CACHE_REFRESH -> CacheRegenerationEnvelope.build(cacheKey, cacheFamily, reason);
        };
    }

    public CacheRegenerationEnvelope buildBatch(String cacheFamily, String targetType,
                                                List<Long> targetIds, CacheRegenerationReason reason) {
        return CacheRegenerationEnvelope.buildBatch(cacheFamily, targetType, targetIds, reason);
    }

    public CacheRegenerationEnvelope buildTarget(String cacheFamily, String targetType,
                                                 CacheRegenerationReason reason) {
        return CacheRegenerationEnvelope.buildTarget(cacheFamily, targetType, reason);
    }
}
