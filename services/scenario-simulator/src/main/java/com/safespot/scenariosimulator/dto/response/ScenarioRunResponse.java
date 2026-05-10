package com.safespot.scenariosimulator.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ScenarioRunResponse {

    private String scenarioId;
    private String scenarioName;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private int disasterAlertsCreated;
    private int residentsCreated;
    private int eventsPublished;
    private boolean cacheRegenerationTriggered;
    private boolean proactiveScaleTriggered;
    private String status;
}
