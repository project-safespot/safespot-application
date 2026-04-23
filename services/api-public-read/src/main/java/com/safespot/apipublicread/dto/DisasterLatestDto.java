package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisasterLatestDto(
        long alertId,
        String disasterType,
        String region,
        String level,
        String message,
        String issuedAt,
        String expiredAt,
        DisasterDetails details
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DisasterDetails(
            Double magnitude,
            String epicenter,
            String intensity
    ) {}
}
