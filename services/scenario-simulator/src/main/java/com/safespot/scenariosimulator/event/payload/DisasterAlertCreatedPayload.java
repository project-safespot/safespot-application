package com.safespot.scenariosimulator.event.payload;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class DisasterAlertCreatedPayload {

    private Long alertId;
    private String disasterType;
    private String region;
    private String level;
    private Integer levelRank;
    private OffsetDateTime issuedAt;
    private String source;
}
