package com.safespot.apipublicread.dto.cache;

public record ShelterMapItemCacheDto(
    int schemaVersion,
    long shelterId,
    String shelterName,
    String shelterType,
    String disasterType,
    String address,
    int capacityTotal,
    Integer currentOccupancy,
    Integer availableCapacity,
    String congestionLevel,
    String shelterStatus,
    double latitude,
    double longitude,
    String updatedAt
) {}
