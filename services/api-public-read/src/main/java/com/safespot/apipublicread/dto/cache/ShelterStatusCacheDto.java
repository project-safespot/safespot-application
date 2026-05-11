package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelterStatusCacheDto(
        int currentOccupancy,
        int availableCapacity,
        String congestionLevel,
        String shelterStatus
) {}
