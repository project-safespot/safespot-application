package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DashboardResponse {

    private final Summary summary;
    private final List<ShelterItem> shelters;

    @Getter
    @Builder
    public static class Summary {
        private final long totalShelters;
        private final long openShelters;
        private final long fullShelters;
    }

    @Getter
    @Builder
    public static class ShelterItem {
        private final Long shelterId;
        private final String shelterName;
        private final String shelterType;
        private final int capacityTotal;
        private final long currentOccupancy;
        private final long availableCapacity;
        private final String congestionLevel;
        private final String shelterStatus;
    }
}
