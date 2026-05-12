package com.safespot.apipublicread.event;

public enum QueueType {
    CACHE_REFRESH("cache-refresh"),
    READMODEL_REFRESH("readmodel-refresh"),
    ENVIRONMENT_CACHE_REFRESH("environment-cache-refresh");

    private final String label;

    QueueType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
