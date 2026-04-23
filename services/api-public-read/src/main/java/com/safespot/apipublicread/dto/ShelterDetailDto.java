package com.safespot.apipublicread.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShelterDetailDto(
        long shelterId,
        String shelterName,
        String shelterType,
        String disasterType,
        String address,
        double latitude,
        double longitude,
        int capacityTotal,
        int currentOccupancy,
        int availableCapacity,
        String congestionLevel,
        String shelterStatus,
        String manager,
        String contact,
        String note,
        String updatedAt
) {}
