package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShelterNearbyItem(
        long shelterId,
        String shelterName,
        String shelterType,
        String disasterType,
        String address,
        double latitude,
        double longitude,
        int distanceM,
        int capacityTotal,
        int currentOccupancy,
        int availableCapacity,
        String congestionLevel,
        String shelterStatus,
        String updatedAt
) {}
