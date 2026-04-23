package com.safespot.apicore.event.springevent;

import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.EvacuationEntryUpdatedPayload;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class EvacuationEntryUpdatedSpringEvent extends ApplicationEvent {

    private final EventEnvelope<EvacuationEntryUpdatedPayload> envelope;

    public EvacuationEntryUpdatedSpringEvent(Object source, EventEnvelope<EvacuationEntryUpdatedPayload> envelope) {
        super(source);
        this.envelope = envelope;
    }
}
