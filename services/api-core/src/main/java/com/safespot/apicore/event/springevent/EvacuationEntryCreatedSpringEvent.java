package com.safespot.apicore.event.springevent;

import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.EvacuationEntryCreatedPayload;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class EvacuationEntryCreatedSpringEvent extends ApplicationEvent {

    private final EventEnvelope<EvacuationEntryCreatedPayload> envelope;

    public EvacuationEntryCreatedSpringEvent(Object source, EventEnvelope<EvacuationEntryCreatedPayload> envelope) {
        super(source);
        this.envelope = envelope;
    }
}
