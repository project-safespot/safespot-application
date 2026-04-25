package com.safespot.externalingestion.publisher.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentDataCollectedEventSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void weatherEvent_timeWindowNotInTopLevel() throws Exception {
        EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
            "trace-001", "WEATHER", "seoul", "2026-04-21T10:00", "2026-04-21T10:05:00+09:00");

        String json = objectMapper.writeValueAsString(event);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("timeWindow")).isFalse();
        assertThat(root.has("eventType")).isTrue();
        assertThat(root.get("eventType").asText()).isEqualTo("EnvironmentDataCollected");
        assertThat(root.has("payload")).isTrue();
        assertThat(root.get("payload").has("timeWindow")).isFalse();
        assertThat(root.get("payload").get("collectionType").asText()).isEqualTo("WEATHER");
        assertThat(root.get("payload").get("region").asText()).isEqualTo("seoul");
    }

    @Test
    void airQualityEvent_timeWindowNotInTopLevel() throws Exception {
        EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
            "trace-002", "AIR_QUALITY", "seoul", "2026-04-21T11:00", "2026-04-21T11:05:00+09:00");

        String json = objectMapper.writeValueAsString(event);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("timeWindow")).isFalse();
        assertThat(root.get("eventType").asText()).isEqualTo("EnvironmentDataCollected");
        assertThat(root.get("payload").get("collectionType").asText()).isEqualTo("AIR_QUALITY");
    }

    @Test
    void idempotencyKeyIncludesTimeWindow() {
        String timeWindow = "2026-04-21T10:00";
        EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
            "trace-003", "WEATHER", "seoul", timeWindow, "2026-04-21T10:05:00+09:00");

        assertThat(event.getIdempotencyKey()).contains(timeWindow);
        assertThat(event.getIdempotencyKey()).startsWith("collected:env:WEATHER:seoul:");
    }

    @Test
    void topLevelFields_matchEventContract() throws Exception {
        EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
            "trace-004", "WEATHER", "seoul", "2026-04-21T10:00", "2026-04-21T10:05:00+09:00");

        String json = objectMapper.writeValueAsString(event);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("eventId")).isTrue();
        assertThat(root.has("occurredAt")).isTrue();
        assertThat(root.has("producer")).isTrue();
        assertThat(root.has("traceId")).isTrue();
        assertThat(root.has("eventType")).isTrue();
        assertThat(root.has("idempotencyKey")).isTrue();
        assertThat(root.has("payload")).isTrue();
        // Only these 7 top-level fields should be present
        assertThat(root.size()).isEqualTo(7);
    }
}
