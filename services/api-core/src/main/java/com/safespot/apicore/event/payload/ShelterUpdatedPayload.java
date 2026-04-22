package com.safespot.apicore.event.payload;

import com.safespot.apicore.domain.enums.DisasterType;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
public class ShelterUpdatedPayload {
    private final Long shelterId;
    private final String shelterType;
    private final DisasterType disasterType;
    private final Long recordedByAdminId;
    private final OffsetDateTime updatedAt;
    private final List<String> changedFields;
}
