package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.exception.ResourceNotFoundException;
import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.repository.EvacuationEntryRepository;
import com.safespot.asyncworker.repository.ShelterInfo;
import com.safespot.asyncworker.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        int availableCapacity = Math.max(0, shelter.capacity() - currentOccupancy);
        CongestionLevel level = CongestionLevel.of(currentOccupancy, shelter.capacity());

        ShelterStatusValue value = new ShelterStatusValue(
            currentOccupancy,
            availableCapacity,
            level.name(),
            shelter.shelterStatus()
        );

        cacheWriter.setShelterStatus(shelterId, value);
        log.info("Shelter status recalculated: shelterId={}, occupancy={}, level={}",
            shelterId, currentOccupancy, level);
    }
}
