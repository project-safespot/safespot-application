package com.safespot.asyncworker.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShelterMapSource(
    Long shelterId,
    String shelterName,
    String shelterType,
    String disasterType,
    String address,
    Integer capacityTotal,
    BigDecimal latitude,
    BigDecimal longitude,
    OffsetDateTime updatedAt
) {}
