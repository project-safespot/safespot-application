package com.safespot.apipublicread.dto.cache;

public record ShelterMapItemCacheDto(
    int schemaVersion,
    long shelterId,
    String shelterName,
    String shelterType,
    String disasterType,
    String address,
    double latitude,
    double longitude,
    String updatedAt
) {}
