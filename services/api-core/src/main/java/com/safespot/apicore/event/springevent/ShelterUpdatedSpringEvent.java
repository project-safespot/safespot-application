package com.safespot.apicore.event.springevent;

import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.ShelterUpdatedPayload;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ShelterUpdatedSpringEvent extends ApplicationEvent {

    private final EventEnvelope<ShelterUpdatedPayload> envelope;

    public ShelterUpdatedSpringEvent(Object source, EventEnvelope<ShelterUpdatedPayload> envelope) {
        super(source);
        this.envelope = envelope;
    }
}
