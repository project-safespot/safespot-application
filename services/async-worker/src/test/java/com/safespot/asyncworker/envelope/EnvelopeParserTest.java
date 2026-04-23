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
    void 선택_필드_누락시_정상_파싱() {
        // occurredAt, producer는 선택 필드
        String body = """
            {
              "eventId": "evt-001",
              "eventType": "ShelterUpdated",
              "traceId": "trace-001",
              "idempotencyKey": "shelter:101:UPDATED",
              "payload": {"shelterId": 101}
            }
            """;
        assertThat(parser.parse(body)).isNotNull();
    }

    @Test
    void eventId_누락시_EnvelopeParseException() {
        String body = """
            {
              "eventType": "ShelterUpdated",
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
