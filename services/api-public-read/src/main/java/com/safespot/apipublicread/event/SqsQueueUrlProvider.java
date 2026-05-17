package com.safespot.apipublicread.event;

import lombok.RequiredArgsConstructor;

import java.util.Optional;

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

    public Optional<String> find(QueueType queueType) {
        return Optional.ofNullable(get(queueType))
                .map(String::trim)
                .filter(value -> !value.isBlank());
    }
}
