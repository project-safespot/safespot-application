package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Getter
public abstract class IngestionEvent {

    @JsonProperty("eventId")
    private final String eventId = UUID.randomUUID().toString();

    @JsonProperty("occurredAt")
    private final String occurredAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    @JsonProperty("producer")
    private final String producer = "external-ingestion";

    @JsonProperty("traceId")
    private final String traceId;

    protected IngestionEvent(String traceId) {
        this.traceId = traceId;
    }

    @JsonProperty("eventType")
    public abstract String getEventType();

    @JsonProperty("idempotencyKey")
    public abstract String getIdempotencyKey();
}
