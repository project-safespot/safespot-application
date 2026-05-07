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
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final ShelterRepository shelterRepository;
    private final EvacuationEntryRepository evacuationEntryRepository;
    private final ApiCoreMetrics metrics;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        List<Shelter> allShelters = shelterRepository.findAll();

        Map<Long, Long> occupancyByShelter = evacuationEntryRepository
                .countByEntryStatusGroupByShelterId(EntryStatus.ENTERED)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));

        long totalShelters = allShelters.size();
        long openShelters = allShelters.stream()
                .filter(s -> s.getShelterStatus() == ShelterStatus.OPERATING)
                .count();

        List<DashboardResponse.ShelterItem> shelterItems = allShelters.stream()
                .map(s -> {
                    long occupancy = occupancyByShelter.getOrDefault(s.getShelterId(), 0L);
                    Integer cap = s.getCapacity();
                    long available = cap != null ? Math.max(0L, cap - occupancy) : 0L;
                    double rate = computeOccupancyRate(cap, occupancy);
                    return DashboardResponse.ShelterItem.builder()
                            .shelterId(s.getShelterId())
                            .shelterName(s.getName())
                            .shelterType(s.getShelterType())
                            .capacityTotal(cap != null ? cap : 0)
                            .currentOccupancy(occupancy)
                            .availableCapacity(available)
                            .congestionLevel(computeCongestionLevel(cap, occupancy))
                            .shelterStatus(s.getShelterStatus().name())
                            .name(s.getName())
                            .address(s.getAddress())
                            .disasterType(s.getDisasterType().name())
                            .currentOccupants(occupancy)
                            .occupancyRate(rate)
                            .crowdingLevel(computeCrowdingLevel(rate))
                            .manager(s.getManager())
                            .build();
                })
                .toList();

        long fullShelters = shelterItems.stream()
                .filter(s -> "FULL".equals(s.getCrowdingLevel()))
                .count();
        long crowdedShelters = shelterItems.stream()
                .filter(s -> "CROWDED".equals(s.getCrowdingLevel()))
                .count();
        long normalShelters = shelterItems.stream()
                .filter(s -> "NORMAL".equals(s.getCrowdingLevel()))
                .count();
        long availableShelters = shelterItems.stream()
                .filter(s -> "AVAILABLE".equals(s.getCrowdingLevel()))
                .count();
        long totalOccupants = shelterItems.stream()
                .mapToLong(DashboardResponse.ShelterItem::getCurrentOccupants)
                .sum();

        metrics.updateShelterCounts(fullShelters, crowdedShelters, openShelters);

        return DashboardResponse.builder()
                .summary(DashboardResponse.Summary.builder()
                        .totalShelters(totalShelters)
                        .openShelters(openShelters)
                        .fullShelters(fullShelters)
                        .availableShelters(availableShelters)
                        .normalShelters(normalShelters)
                        .crowdedShelters(crowdedShelters)
                        .totalOccupants(totalOccupants)
                        .build())
                .shelters(shelterItems)
                .build();
    }

    private double computeOccupancyRate(Integer capacity, long occupancy) {
        if (capacity == null || capacity == 0) return occupancy > 0 ? 100.0 : 0.0;
        return (double) occupancy / capacity * 100;
    }

    private String computeCrowdingLevel(double rate) {
        if (rate >= 100) return "FULL";
        if (rate >= 80) return "CROWDED";
        if (rate >= 50) return "NORMAL";
        return "AVAILABLE";
    }

    private String computeCongestionLevel(Integer capacity, long occupancy) {
        if (capacity == null) return "AVAILABLE";
        if (capacity == 0) return "FULL";
        double rate = (double) occupancy / capacity * 100;
        if (rate >= 100) return "FULL";
        if (rate >= 75) return "CROWDED";
        if (rate >= 50) return "NORMAL";
        return "AVAILABLE";
    }
}
