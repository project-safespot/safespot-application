package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class ShelterDataCollectedEvent extends IngestionEvent {

    @JsonProperty("payload")
    private final Payload payload;

    public ShelterDataCollectedEvent(String traceId, String disasterType, int savedCount, String completedAt) {
        super(traceId);
        this.payload = new Payload(disasterType, savedCount, completedAt);
    }

    @Override
    public String getEventType() {
        return "ShelterDataCollected";
    }

    @Override
    public String getIdempotencyKey() {
        return "collected:shelter:" + payload.getDisasterType() + ":" + payload.getCompletedAt();
    }

    @Getter
    public static class Payload {
        @JsonProperty("disasterType")
        private final String disasterType;
        @JsonProperty("savedCount")
        private final int savedCount;
        @JsonProperty("completedAt")
        private final String completedAt;

        Payload(String disasterType, int savedCount, String completedAt) {
            this.disasterType = disasterType;
            this.savedCount = savedCount;
            this.completedAt = completedAt;
        }
    }
}
