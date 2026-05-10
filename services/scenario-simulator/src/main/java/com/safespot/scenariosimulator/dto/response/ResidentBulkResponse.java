package com.safespot.scenariosimulator.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class ResidentBulkResponse {

    private String scenarioId;
    private int requestedCount;
    private int createdCount;
    private int shelterCount;
    private Map<Long, Integer> entriesPerShelter;
    private int eventsPublished;
}
