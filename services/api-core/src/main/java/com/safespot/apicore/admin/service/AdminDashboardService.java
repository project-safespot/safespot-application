package com.safespot.apicore.admin.service;

import com.safespot.apicore.admin.dto.DashboardResponse;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.EntryStatus;
import com.safespot.apicore.domain.enums.ShelterStatus;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.EvacuationEntryRepository;
import com.safespot.apicore.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final ApiCoreMetrics metrics;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        List<Shelter> allShelters = shelterRepository.findAll();
        long totalShelters = allShelters.size();
        long openShelters = allShelters.stream()
                .filter(s -> s.getShelterStatus() == ShelterStatus.운영중)
                .count();

        List<DashboardResponse.ShelterItem> shelterItems = allShelters.stream()
                .map(s -> {
                    long occupancy = evacuationEntryRepository.countByShelterIdAndEntryStatus(
                            s.getShelterId(), EntryStatus.ENTERED);
                    long available = Math.max(0, s.getCapacity() - occupancy);
                    String congestion = computeCongestionLevel(s.getCapacity(), occupancy);
                    return DashboardResponse.ShelterItem.builder()
                            .shelterId(s.getShelterId())
                            .shelterName(s.getName())
                            .shelterType(s.getShelterType())
                            .capacityTotal(s.getCapacity())
                            .currentOccupancy(occupancy)
                            .availableCapacity(available)
                            .congestionLevel(congestion)
                            .shelterStatus(s.getShelterStatus().name())
                            .build();
                })
                .toList();

        long fullShelters = shelterItems.stream()
                .filter(s -> "FULL".equals(s.getCongestionLevel()))
                .count();
        long crowdedShelters = shelterItems.stream()
                .filter(s -> "CROWDED".equals(s.getCongestionLevel()))
                .count();

        metrics.updateShelterCounts(fullShelters, crowdedShelters, openShelters);

        return DashboardResponse.builder()
                .summary(DashboardResponse.Summary.builder()
                        .totalShelters(totalShelters)
                        .openShelters(openShelters)
                        .fullShelters(fullShelters)
                        .build())
                .shelters(shelterItems)
                .build();
    }

    private String computeCongestionLevel(int capacity, long occupancy) {
        if (capacity == 0) return "FULL";
        double rate = (double) occupancy / capacity * 100;
        if (rate >= 100) return "FULL";
        if (rate >= 75) return "CROWDED";
        if (rate >= 50) return "NORMAL";
        return "AVAILABLE";
    }
}
