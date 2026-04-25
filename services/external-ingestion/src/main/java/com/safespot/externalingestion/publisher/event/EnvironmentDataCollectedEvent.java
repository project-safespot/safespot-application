package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class EnvironmentDataCollectedEvent extends IngestionEvent {

    @JsonProperty("payload")
    private final Payload payload;

    @JsonIgnore
    private final String timeWindow;

    public EnvironmentDataCollectedEvent(String traceId, String collectionType, String region,
                                         String timeWindow, String completedAt) {
        super(traceId);
        this.timeWindow = timeWindow;
        this.payload = new Payload(collectionType, region, completedAt);
    }

    @Override
    public String getEventType() {
        return "EnvironmentDataCollected";
    }

    @Override
    public String getIdempotencyKey() {
        return "collected:env:" + payload.getCollectionType() + ":" + payload.getRegion() + ":" + timeWindow;
    }

    @Getter
    public static class Payload {
        @JsonProperty("collectionType")
        private final String collectionType;
        @JsonProperty("region")
        private final String region;
        @JsonProperty("completedAt")
        private final String completedAt;

        Payload(String collectionType, String region, String completedAt) {
            this.collectionType = collectionType;
            this.region = region;
            this.completedAt = completedAt;
        }
    }
}
