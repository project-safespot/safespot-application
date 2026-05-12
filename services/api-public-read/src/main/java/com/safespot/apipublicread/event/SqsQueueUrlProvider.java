package com.safespot.apipublicread.event;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SqsQueueUrlProvider {

    private final String cacheRefreshQueueUrl;
    private final String readModelRefreshQueueUrl;
    private final String environmentCacheRefreshQueueUrl;

    public String get(QueueType queueType) {
        return switch (queueType) {
            case CACHE_REFRESH -> cacheRefreshQueueUrl;
            case READMODEL_REFRESH -> readModelRefreshQueueUrl;
            case ENVIRONMENT_CACHE_REFRESH -> environmentCacheRefreshQueueUrl;
        };
    }
}
