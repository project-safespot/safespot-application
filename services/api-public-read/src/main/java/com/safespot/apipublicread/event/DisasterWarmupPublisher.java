package com.safespot.apipublicread.event;

public interface DisasterWarmupPublisher {
    void publish(int limit, boolean includeDetails);
}
