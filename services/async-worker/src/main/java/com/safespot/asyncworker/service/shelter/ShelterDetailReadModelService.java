package com.safespot.asyncworker.service.shelter;

import com.safespot.asyncworker.redis.RedisCacheWriter;
import com.safespot.asyncworker.redis.ShelterDetailValue;
import com.safespot.asyncworker.repository.ShelterDetailSource;
import com.safespot.asyncworker.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

@Profile({"cache-worker", "async-worker"})
@Slf4j
@Service
@RequiredArgsConstructor
public class ShelterDetailReadModelService {

    static final int DETAIL_SCHEMA_VERSION = 1;

    private final ShelterRepository shelterRepository;
    private final RedisCacheWriter cacheWriter;

    public void rebuildAllDetails() {
        List<ShelterDetailSource> sources = shelterRepository.findAllForDetailReadModel();
        int writtenCount = 0;
        for (ShelterDetailSource source : sources) {
            if (writeDetail(source)) {
                writtenCount++;
            }
        }
        log.info("Shelter detail read models rebuilt (all): sourceCount={}, writtenCount={}", sources.size(), writtenCount);
    }

    public void rebuildDetails(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) {
            rebuildAllDetails();
            return;
        }
        List<Long> distinctIds = shelterIds.stream().distinct().toList();
        List<ShelterDetailSource> sources = shelterRepository.findByIdsForDetailReadModel(distinctIds);
        int writtenCount = 0;
        for (ShelterDetailSource source : sources) {
            if (writeDetail(source)) {
                writtenCount++;
            }
        }
        log.info("Shelter detail read models rebuilt: requestedCount={}, foundCount={}, writtenCount={}",
            distinctIds.size(), sources.size(), writtenCount);
    }

    private boolean writeDetail(ShelterDetailSource source) {
        if (source.latitude() == null || source.longitude() == null || source.updatedAt() == null) {
            log.warn("Skipping shelter detail source with missing coordinates or updatedAt: shelterId={}", source.shelterId());
            return false;
        }
        cacheWriter.setShelterDetail(source.shelterId(), new ShelterDetailValue(
            DETAIL_SCHEMA_VERSION,
            source.shelterId(),
            source.name(),
            source.shelterType(),
            source.disasterType(),
            source.address(),
            source.latitude().doubleValue(),
            source.longitude().doubleValue(),
            Math.max(0, source.capacity() != null ? source.capacity() : 0),
            source.manager(),
            source.contact(),
            source.note(),
            source.updatedAt().toString()
        ));
        return true;
    }
}
