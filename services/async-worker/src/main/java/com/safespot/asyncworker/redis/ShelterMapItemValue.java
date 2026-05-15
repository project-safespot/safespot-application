package com.safespot.asyncworker.redis;

public record ShelterMapItemValue(
    int schemaVersion,
    Long shelterId,
    String shelterName,
    String shelterType,
    String disasterType,
    String address,
    double latitude,
    double longitude,
    String updatedAt
) {}
