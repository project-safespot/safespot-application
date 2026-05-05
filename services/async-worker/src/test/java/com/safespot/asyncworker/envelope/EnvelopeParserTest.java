package com.safespot.asyncworker.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeParserTest {

    private EnvelopeParser parser;

    @BeforeEach
    void setUp() {
        parser = new EnvelopeParser(new ObjectMapper());
    }

    @Test
    void 정상_Envelope_파싱() {
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "EvacuationEntryCreated",
              "occurredAt": "2026-04-15T12:00:00+09:00",
              "producer": "api-core",
              "traceId": "trace-001",
              "idempotencyKey": "entry:301:ENTERED",
              "payload": {"entryId": 301, "shelterId": 101}
            }
            """;
        EventEnvelope envelope = parser.parse(body);
        assertThat(envelope.getEventId()).isEqualTo("evt-001");
        assertThat(envelope.getEventType()).isEqualTo("EvacuationEntryCreated");
    }

    @Test
    void occurredAt_누락시_EnvelopeParseException() {
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "ShelterUpdated",
              "producer": "api-core",
              "traceId": "trace-001",
              "idempotencyKey": "shelter:101:UPDATED",
              "payload": {"shelterId": 101}
            }
            """;
        assertThatThrownBy(() -> parser.parse(body))
            .isInstanceOf(EnvelopeParseException.class)
            .hasMessageContaining("occurredAt");
    }

    @Test
    void producer_누락시_EnvelopeParseException() {
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "ShelterUpdated",
              "occurredAt": "2026-04-15T12:00:00+09:00",
              "traceId": "trace-001",
              "idempotencyKey": "shelter:101:UPDATED",
              "payload": {"shelterId": 101}
            }
            """;
        assertThatThrownBy(() -> parser.parse(body))
            .isInstanceOf(EnvelopeParseException.class)
            .hasMessageContaining("producer");
    }

    @Test
    void eventId_누락시_EnvelopeParseException() {
        String body = """
            {
              "eventType": "ShelterUpdated",
              "occurredAt": "2026-04-15T12:00:00+09:00",
              "producer": "api-core",
              "traceId": "trace-001",
              "idempotencyKey": "shelter:101:UPDATED",
              "payload": {}
            }
            """;
        assertThatThrownBy(() -> parser.parse(body))
            .isInstanceOf(EnvelopeParseException.class)
            .hasMessageContaining("eventId");
    }

    @Test
    void payload_누락시_EnvelopeParseException() {
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "ShelterUpdated",
              "occurredAt": "2026-04-15T12:00:00+09:00",
              "producer": "api-core",
              "traceId": "trace-001",
              "idempotencyKey": "shelter:101:UPDATED"
            }
            """;
        assertThatThrownBy(() -> parser.parse(body))
            .isInstanceOf(EnvelopeParseException.class)
            .hasMessageContaining("payload");
    }

    @Test
    void 알수없는_eventType_EnvelopeParseException() {
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "UnknownEvent",
              "occurredAt": "2026-04-15T12:00:00+09:00",
              "producer": "api-core",
              "traceId": "trace-001",
              "idempotencyKey": "key-001",
              "payload": {}
            }
            """;
        assertThatThrownBy(() -> parser.parse(body))
            .isInstanceOf(EnvelopeParseException.class);
    }

    @Test
    void 잘못된_JSON_EnvelopeParseException() {
        assertThatThrownBy(() -> parser.parse("{invalid json}"))
            .isInstanceOf(EnvelopeParseException.class);
    }
}
