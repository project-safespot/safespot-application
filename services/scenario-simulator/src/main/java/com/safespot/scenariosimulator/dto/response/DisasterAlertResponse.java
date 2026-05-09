package com.safespot.scenariosimulator.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DisasterAlertResponse {

    private String scenarioId;
    private int requestedCount;
    private int createdCount;
    private List<Long> alertIds;
    private int eventsPublished;
}
