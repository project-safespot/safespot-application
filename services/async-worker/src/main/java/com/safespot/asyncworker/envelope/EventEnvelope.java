package com.safespot.asyncworker.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class EventEnvelope {
    private String eventId;
    private String eventType;
    private String occurredAt;
    private String producer;
    private String traceId;
    private String idempotencyKey;
    private JsonNode payload;
}
