package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AdminShelterPageResponse {

    private final List<ShelterItem> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean hasNext;

    @Getter
    @Builder
    public static class ShelterItem {
        private final Long shelterId;
        private final String name;
        private final String address;
        private final String shelterType;
        private final String disasterType;
        private final Integer capacity;
        private final Long currentOccupants;
        private final Long availableCapacity;
        private final Double occupancyRate;
        private final String crowdingLevel;
        private final String shelterStatus;
        private final String manager;
        private final String contact;
    }
}
