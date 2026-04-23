package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class DisasterAlertCacheRefreshEvent extends CacheRefreshEvent {

    @JsonProperty("payload")
    private final Payload payload;

    public DisasterAlertCacheRefreshEvent(String traceId, Long alertId, String region, String disasterType) {
        super(traceId);
        this.payload = new Payload(alertId, region, disasterType);
    }

    @Override
    public String getEventType() {
        return "DisasterAlertCacheRefresh";
    }

    @Override
    public String getIdempotencyKey() {
        return "alert:" + payload.alertId + ":CACHE_REFRESH";
    }

    @Getter
    public static class Payload {
        @JsonProperty("alertId")
        private final Long alertId;
        @JsonProperty("region")
        private final String region;
        @JsonProperty("disasterType")
        private final String disasterType;

        Payload(Long alertId, String region, String disasterType) {
            this.alertId = alertId;
            this.region = region;
            this.disasterType = disasterType;
        }
    }
}
