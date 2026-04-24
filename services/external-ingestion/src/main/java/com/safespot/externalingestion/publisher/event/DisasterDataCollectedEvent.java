package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

@Getter
public class DisasterDataCollectedEvent extends IngestionEvent {

    @JsonProperty("payload")
    private final Payload payload;

    public DisasterDataCollectedEvent(String traceId, String collectionType, String region,
                                      List<Long> affectedAlertIds, boolean hasExpiredAlerts,
                                      String completedAt) {
        super(traceId);
        this.payload = new Payload(collectionType, region, affectedAlertIds, hasExpiredAlerts, completedAt);
    }

    @Override
    public String getEventType() {
        return "DisasterDataCollected";
    }

    @Override
    public String getIdempotencyKey() {
        return "collected:disaster:" + payload.getCollectionType() + ":" + payload.getRegion() + ":" + payload.getCompletedAt();
    }

    @Getter
    public static class Payload {
        @JsonProperty("collectionType")
        private final String collectionType;
        @JsonProperty("region")
        private final String region;
        @JsonProperty("affectedAlertIds")
        private final List<Long> affectedAlertIds;
        @JsonProperty("hasExpiredAlerts")
        private final boolean hasExpiredAlerts;
        @JsonProperty("completedAt")
        private final String completedAt;

        Payload(String collectionType, String region, List<Long> affectedAlertIds,
                boolean hasExpiredAlerts, String completedAt) {
            this.collectionType = collectionType;
            this.region = region;
            this.affectedAlertIds = affectedAlertIds;
            this.hasExpiredAlerts = hasExpiredAlerts;
            this.completedAt = completedAt;
        }
    }
}
