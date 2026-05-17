package com.safespot.asyncworker.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShelterDetailSource(
    Long shelterId,
    String name,
    String shelterType,
    String disasterType,
    String address,
    Integer capacity,
    String manager,
    String contact,
    String note,
    BigDecimal latitude,
    BigDecimal longitude,
    OffsetDateTime updatedAt
) {}
