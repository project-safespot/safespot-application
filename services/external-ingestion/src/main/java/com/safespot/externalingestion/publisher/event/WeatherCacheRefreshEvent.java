package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class WeatherCacheRefreshEvent extends CacheRefreshEvent {

    @JsonProperty("payload")
    private final Payload payload;

    public WeatherCacheRefreshEvent(String traceId, int nx, int ny) {
        super(traceId);
        this.payload = new Payload(nx, ny);
    }

    @Override
    public String getEventType() {
        return "WeatherCacheRefresh";
    }

    @Override
    public String getIdempotencyKey() {
        return "env:weather:" + payload.nx + ":" + payload.ny + ":CACHE_REFRESH";
    }

    @Getter
    public static class Payload {
        @JsonProperty("type")
        private final String type = "WEATHER";
        @JsonProperty("nx")
        private final int nx;
        @JsonProperty("ny")
        private final int ny;

        Payload(int nx, int ny) {
            this.nx = nx;
            this.ny = ny;
        }
    }
}
