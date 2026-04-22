package com.safespot.asyncworker.handler;

import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;

public interface EventHandler {

    EventType supportedEventType();

    void handle(EventEnvelope envelope);
}
