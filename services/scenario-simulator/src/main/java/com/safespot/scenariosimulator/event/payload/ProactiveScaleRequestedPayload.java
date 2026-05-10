package com.safespot.scenariosimulator.event.payload;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProactiveScaleRequestedPayload {

    private String disasterType;
    private String region;
    private String level;
    private int requestedReplicas;
}
