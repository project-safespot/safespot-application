package com.safespot.apipublicread.event;

import org.springframework.stereotype.Component;

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
}
