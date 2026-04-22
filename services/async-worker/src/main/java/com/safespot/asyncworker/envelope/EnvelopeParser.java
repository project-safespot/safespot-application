package com.safespot.asyncworker.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class EnvelopeParser {

    private static final String[] REQUIRED_FIELDS = {
        "eventId", "eventType", "traceId", "idempotencyKey", "payload"
    };

    private final ObjectMapper objectMapper;

    public EventEnvelope parse(String messageBody) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(messageBody, EventEnvelope.class);
        } catch (Exception e) {
            throw new EnvelopeParseException("Failed to deserialize SQS message body", e);
        }

        validateRequiredFields(envelope);
        return envelope;
    }

    private void validateRequiredFields(EventEnvelope envelope) {
        if (!StringUtils.hasText(envelope.getEventId())) {
            throw new EnvelopeParseException("Missing required field: eventId");
        }
        if (!StringUtils.hasText(envelope.getEventType())) {
            throw new EnvelopeParseException("Missing required field: eventType");
        }
        if (!StringUtils.hasText(envelope.getTraceId())) {
            throw new EnvelopeParseException("Missing required field: traceId");
        }
        if (!StringUtils.hasText(envelope.getIdempotencyKey())) {
            throw new EnvelopeParseException("Missing required field: idempotencyKey");
        }
        if (envelope.getPayload() == null || envelope.getPayload().isNull()) {
            throw new EnvelopeParseException("Missing required field: payload");
        }
        // eventType 값이 유효한지 사전 검증
        EventType.from(envelope.getEventType());
    }
}
