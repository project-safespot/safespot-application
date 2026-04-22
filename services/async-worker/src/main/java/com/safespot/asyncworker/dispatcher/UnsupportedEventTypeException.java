package com.safespot.asyncworker.dispatcher;

import com.safespot.asyncworker.exception.EventProcessingException;

public class UnsupportedEventTypeException extends EventProcessingException {

    public UnsupportedEventTypeException(String eventType) {
        super("No handler registered for eventType: " + eventType);
    }
}
