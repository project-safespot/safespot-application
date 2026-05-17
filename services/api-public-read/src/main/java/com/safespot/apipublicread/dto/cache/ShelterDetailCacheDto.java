package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShelterDetailCacheDto(
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
