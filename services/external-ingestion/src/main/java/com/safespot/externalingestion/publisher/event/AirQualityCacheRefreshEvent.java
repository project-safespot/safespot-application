package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AirQualityCacheRefreshEvent extends CacheRefreshEvent {

    @JsonProperty("payload")
    private final Payload payload;

    public AirQualityCacheRefreshEvent(String traceId, String stationName) {
        super(traceId);
        this.payload = new Payload(stationName);
    }

    @Override
    public String getEventType() {
        return "AirQualityCacheRefresh";
    }

    @Override
    public String getIdempotencyKey() {
        return "env:air:" + payload.stationName + ":CACHE_REFRESH";
    }

    @Getter
    public static class Payload {
        @JsonProperty("type")
        private final String type = "AIR_QUALITY";
        @JsonProperty("stationName")
        private final String stationName;

        Payload(String stationName) {
            this.stationName = stationName;
        }
    }
}
