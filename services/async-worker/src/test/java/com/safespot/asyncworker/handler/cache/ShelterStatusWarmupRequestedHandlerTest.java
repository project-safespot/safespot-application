package com.safespot.asyncworker.handler.cache;

import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShelterStatusWarmupRequestedHandlerTest {

    @Mock private ShelterStatusService shelterStatusService;

    @InjectMocks private ShelterStatusWarmupRequestedHandler handler;

    @Test
    void supportedEventType_returnsShelterStatusWarmupRequested() {
        assertThat(handler.supportedEventType()).isEqualTo(EventType.ShelterStatusWarmupRequested);
    }

    @Test
    void handle_invokesWarmUpAll() {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setTraceId("trace-123");
        when(shelterStatusService.warmUpAll()).thenReturn(3);

        handler.handle(envelope);

        verify(shelterStatusService).warmUpAll();
    }
}
