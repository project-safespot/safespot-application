package com.safespot.scenariosimulator.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CleanupResponse {

    private String scenarioId;
    private int disasterAlertsDeleted;
    private int evacuationEntriesDeleted;
    private int trackingRecordsDeleted;
    private List<String> skippedReasons;
}
