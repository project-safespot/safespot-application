package com.safespot.asyncworker.service.shelter;

public record ShelterStatusValue(
    int currentOccupancy,
    int availableCapacity,
    String congestionLevel,
    String shelterStatus
) {}
