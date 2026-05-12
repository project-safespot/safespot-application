package com.safespot.apipublicread.event;

public record CacheRegenerationRoute(QueueType queueType) {

    public String envelopeType() {
        return queueType.label();
    }
}
