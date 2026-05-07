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
        private final long availableShelters;
        private final long normalShelters;
        private final long crowdedShelters;
        private final long totalOccupants;
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
        private final String name;
        private final String address;
        private final String disasterType;
        private final long currentOccupants;
        private final double occupancyRate;
        private final String crowdingLevel;
        private final String manager;
    }
}
