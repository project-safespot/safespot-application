package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Getter
public abstract class CacheRefreshEvent {

    @JsonProperty("eventId")
    private final String eventId = UUID.randomUUID().toString();

    @JsonProperty("occurredAt")
    private final String occurredAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

    @JsonProperty("version")
    private final int version = 1;

    @JsonProperty("producer")
    private final String producer = "external-ingestion";

    @JsonProperty("traceId")
    private final String traceId;

    @JsonProperty("eventType")
    public abstract String getEventType();

    @JsonProperty("idempotencyKey")
    public abstract String getIdempotencyKey();

    protected CacheRefreshEvent(String traceId) {
        this.traceId = traceId;
    }
}
