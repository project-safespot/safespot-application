package com.safespot.apipublicread.dto;

public record ShelterStatusCache(
        int currentOccupancy,
        int availableCapacity,
        String congestionLevel,
        String shelterStatus,
        String updatedAt
) {}
