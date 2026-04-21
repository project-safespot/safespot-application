package com.safespot.apipublicread.event;

public interface CacheRegenerationPublisher {
    void publish(String redisKey);
}
