package com.safespot.apicore.event.springevent;

import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.EvacuationEntryExitedPayload;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class EvacuationEntryExitedSpringEvent extends ApplicationEvent {

    private final EventEnvelope<EvacuationEntryExitedPayload> envelope;

    public EvacuationEntryExitedSpringEvent(Object source, EventEnvelope<EvacuationEntryExitedPayload> envelope) {
        super(source);
        this.envelope = envelope;
    }
}
