package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.exception.ResourceNotFoundException;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.EvacuationEntryRepository;
import com.safespot.asyncworker.repository.ShelterInfo;
import com.safespot.asyncworker.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Profile({"cache-worker", "async-worker"})
@Slf4j
@Service
@RequiredArgsConstructor
public class ShelterStatusService {

    private final EvacuationEntryRepository entryRepository;
    private final ShelterRepository shelterRepository;
    private final RedisCacheWriter cacheWriter;

    public void recalculate(Long shelterId) {
        ShelterInfo shelter = shelterRepository.findById(shelterId)
            .orElseThrow(() -> new ResourceNotFoundException("shelter", shelterId));

        int currentOccupancy = entryRepository.countEntered(shelterId);
        writeStatus(shelter, currentOccupancy);
        log.info("Shelter status recalculated: shelterId={}, occupancy={}, level={}",
            shelterId, currentOccupancy, CongestionLevel.of(currentOccupancy, shelter.capacity()));
    }

    public int warmUpAll() {
        List<ShelterInfo> shelters = shelterRepository.findAllForStatusWarmup();
        if (shelters.isEmpty()) {
            log.info("Shelter status warm-up skipped: no shelters found");
            return 0;
        }

        List<Long> shelterIds = shelters.stream()
            .map(ShelterInfo::shelterId)
            .toList();
        Map<Long, Integer> occupancyByShelterId = entryRepository.countEnteredByShelterIds(shelterIds);

        int count = 0;
        for (ShelterInfo shelter : shelters) {
            int currentOccupancy = occupancyByShelterId.getOrDefault(shelter.shelterId(), 0);
            writeStatus(shelter, currentOccupancy);
            count++;
        }

        log.info("Shelter status warm-up completed: count={}", count);
        return count;
    }

    private void writeStatus(ShelterInfo shelter, int currentOccupancy) {
        Integer capacity = shelter.capacity();
        CongestionLevel level = CongestionLevel.of(currentOccupancy, capacity);
        int availableCapacity = (capacity == null) ? 0 : Math.max(0, capacity - currentOccupancy);

        ShelterStatusValue value = new ShelterStatusValue(
            currentOccupancy,
            availableCapacity,
            level.name(),
            shelter.shelterStatus()
        );

        cacheWriter.setShelterStatus(shelter.shelterId(), value);
    }
}
