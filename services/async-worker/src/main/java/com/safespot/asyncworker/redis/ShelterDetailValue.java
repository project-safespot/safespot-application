package com.safespot.asyncworker.redis;

public record ShelterDetailValue(
    int schemaVersion,
    Long shelterId,
    String name,
    String shelterType,
    String disasterType,
    String address,
    double latitude,
    double longitude,
    int capacity,
    String manager,
    String contact,
    String note,
    String updatedAt
) {}
