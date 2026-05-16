package com.safespot.apipublicread.event;

import com.safespot.apipublicread.cache.RedisReadCache;

public enum CacheRegenerationReason {
    CACHE_MISS("cache_miss"),
    STALE("stale"),
    REDIS_DOWN("redis_down"),
    PARSE_ERROR("parse_error");

    private final String value;

    CacheRegenerationReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CacheRegenerationReason from(RedisReadCache.FallbackReason reason) {
        return switch (reason) {
            case REDIS_DOWN -> REDIS_DOWN;
            case PARSE_ERROR -> PARSE_ERROR;
            case REDIS_MISS -> CACHE_MISS;
        };
    }
}
