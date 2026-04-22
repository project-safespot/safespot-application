package com.safespot.asyncworker.dispatcher;

import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.handler.EventHandler;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EventDispatcher {

    private final Map<EventType, EventHandler> handlerMap;

    public EventDispatcher(List<EventHandler> handlers) {
        this.handlerMap = handlers.stream()
            .collect(Collectors.toMap(EventHandler::supportedEventType, Function.identity()));
    }

    public void dispatch(EventEnvelope envelope) {
        EventType eventType = EventType.from(envelope.getEventType());
        EventHandler handler = handlerMap.get(eventType);
        if (handler == null) {
            throw new UnsupportedEventTypeException(envelope.getEventType());
        }
        handler.handle(envelope);
    }
}
