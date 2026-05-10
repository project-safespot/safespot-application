package com.safespot.scenariosimulator.service;

import com.safespot.scenariosimulator.domain.entity.SimEvacuationEntry;
import com.safespot.scenariosimulator.domain.entity.SimShelter;
import com.safespot.scenariosimulator.domain.entity.TestScenarioRecord;
import com.safespot.scenariosimulator.domain.enums.ResidentDistribution;
import com.safespot.scenariosimulator.dto.request.ResidentBulkRequest;
import com.safespot.scenariosimulator.dto.response.ResidentBulkResponse;
import com.safespot.scenariosimulator.event.EventEnvelope;
import com.safespot.scenariosimulator.event.SimulatorEventPublisher;
import com.safespot.scenariosimulator.event.payload.CacheRegenerationRequestedPayload;
import com.safespot.scenariosimulator.event.payload.EvacuationEntryCreatedPayload;
import com.safespot.scenariosimulator.metrics.SimulatorMetrics;
import com.safespot.scenariosimulator.repository.SimEvacuationEntryRepository;
import com.safespot.scenariosimulator.repository.SimShelterRepository;
import com.safespot.scenariosimulator.repository.TestScenarioRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResidentBulkSimulatorService {

    private static final DateTimeFormatter SCENARIO_NAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SimShelterRepository shelterRepository;
    private final SimEvacuationEntryRepository evacuationEntryRepository;
    private final TestScenarioRecordRepository scenarioRecordRepository;
    private final SimulatorEventPublisher eventPublisher;
    private final SimulatorMetrics metrics;

    @Transactional
    public ResidentBulkResponse createBulkResidents(ResidentBulkRequest request, String scenarioId) {
        String scenarioName = resolveScenarioName(request);

        log.info("[SIM] bulk residents: scenarioId={} scenarioName={} type={} count={} distribution={}",
                scenarioId, scenarioName, request.getDisasterType(), request.getResidentCount(), request.getDistribution());

        List<SimShelter> shelters = loadShelters(request);
        if (shelters.isEmpty()) {
            log.warn("[SIM] no OPERATING shelters found for type={}", request.getDisasterType());
            return ResidentBulkResponse.builder()
                    .scenarioId(scenarioId)
                    .requestedCount(request.getResidentCount())
                    .createdCount(0)
                    .shelterCount(0)
                    .entriesPerShelter(Collections.emptyMap())
                    .eventsPublished(0)
                    .build();
        }

        int[] distribution = distribute(shelters, request.getResidentCount(), request.getDistribution());
        Map<Long, Integer> entriesPerShelter = new LinkedHashMap<>();
        int createdCount = 0;
        int eventsPublished = 0;

        for (int i = 0; i < shelters.size(); i++) {
            SimShelter shelter = shelters.get(i);
            int count = distribution[i];
            if (count == 0) continue;

            for (int j = 0; j < count; j++) {
                SimEvacuationEntry entry = SimEvacuationEntry.builder()
                        .shelterId(shelter.getShelterId())
                        .entryStatus(request.getEntryStatus())
                        .visitorName("SIM_VISITOR_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .note("scenario-simulator:" + scenarioId)
                        .build();

                SimEvacuationEntry saved = evacuationEntryRepository.save(entry);
                createdCount++;

                scenarioRecordRepository.save(TestScenarioRecord.builder()
                        .scenarioId(scenarioId)
                        .scenarioName(scenarioName)
                        .targetTable("evacuation_entry")
                        .targetId(saved.getEntryId())
                        .build());

                if (request.isPublishEvents()) {
                    publishEntryEvent(saved);
                    eventsPublished++;
                }
            }

            entriesPerShelter.put(shelter.getShelterId(), count);
        }

        if (request.isPublishEvents() && createdCount > 0) {
            publishShelterCacheRegen(shelters);
            eventsPublished += shelters.size();
        }

        metrics.incResidentsCreated(createdCount);

        log.info("[SIM] bulk residents done: scenarioId={} created={} eventsPublished={}",
                scenarioId, createdCount, eventsPublished);

        return ResidentBulkResponse.builder()
                .scenarioId(scenarioId)
                .requestedCount(request.getResidentCount())
                .createdCount(createdCount)
                .shelterCount(shelters.size())
                .entriesPerShelter(entriesPerShelter)
                .eventsPublished(eventsPublished)
                .build();
    }

    private String resolveScenarioName(ResidentBulkRequest request) {
        if (request.getScenarioName() != null && !request.getScenarioName().isBlank()) {
            return request.getScenarioName();
        }

        return sanitizeScenarioName("BULK_RESIDENTS_"
                + request.getDisasterType() + "_"
                + request.getRegion() + "_"
                + OffsetDateTime.now().format(SCENARIO_NAME_TIMESTAMP));
    }

    private String sanitizeScenarioName(String scenarioName) {
        return scenarioName.replaceAll("\\s+", "_");
    }

    private List<SimShelter> loadShelters(ResidentBulkRequest request) {
        List<SimShelter> shelters = shelterRepository.findByDisasterTypeAndShelterStatus(
                request.getDisasterType().name(), "OPERATING");
        if (shelters.isEmpty()) {
            shelters = shelterRepository.findByShelterStatus("OPERATING");
        }
        return shelters;
    }

    int[] distribute(List<SimShelter> shelters, int total, ResidentDistribution distribution) {
        int n = shelters.size();
        int[] result = new int[n];

        switch (distribution) {
            case RANDOM -> distributeRandom(result, total, n);
            case WEIGHTED_BY_CAPACITY -> distributeByCapacity(result, shelters, total);
            case HOTSPOT -> distributeHotspot(result, total, n);
        }

        return result;
    }

    private void distributeRandom(int[] result, int total, int n) {
        Random random = new Random();
        for (int i = 0; i < total; i++) {
            result[random.nextInt(n)]++;
        }
    }

    private void distributeByCapacity(int[] result, List<SimShelter> shelters, int total) {
        int totalCapacity = shelters.stream()
                .mapToInt(s -> s.getCapacity() != null ? s.getCapacity() : 1)
                .sum();
        if (totalCapacity == 0) {
            distributeRandom(result, total, shelters.size());
            return;
        }

        int assigned = 0;
        for (int i = 0; i < shelters.size(); i++) {
            int cap = shelters.get(i).getCapacity() != null ? shelters.get(i).getCapacity() : 1;
            int share = (i == shelters.size() - 1)
                    ? total - assigned
                    : (int) Math.round((double) cap / totalCapacity * total);
            result[i] = share;
            assigned += share;
        }
    }

    private void distributeHotspot(int[] result, int total, int n) {
        // 80% to first 20% of shelters (hotspot), remaining 20% distributed randomly
        int hotspotCount = Math.min(n, Math.max(1, (int) Math.ceil(n * 0.2)));
        int hotspotTotal = (int) (total * 0.8);
        int remaining = total - hotspotTotal;

        int hotspotEach = hotspotTotal / hotspotCount;
        for (int i = 0; i < hotspotCount; i++) {
            result[i] = hotspotEach;
        }
        result[0] += hotspotTotal - (hotspotEach * hotspotCount);

        int nonHotspotCount = n - hotspotCount;
        if (nonHotspotCount <= 0) {
            // All shelters are hotspot; put remaining in first shelter
            result[0] += remaining;
        } else {
            Random random = new Random();
            for (int i = 0; i < remaining; i++) {
                result[hotspotCount + random.nextInt(nonHotspotCount)]++;
            }
        }
    }

    private void publishEntryEvent(SimEvacuationEntry entry) {
        EvacuationEntryCreatedPayload payload = EvacuationEntryCreatedPayload.builder()
                .entryId(entry.getEntryId())
                .shelterId(entry.getShelterId())
                .nextStatus("ENTERED")
                .enteredAt(entry.getEnteredAt())
                .build();

        String idempotencyKey = "entry:" + entry.getEntryId() + ":ENTERED";
        eventPublisher.publish(EventEnvelope.of("EvacuationEntryCreated", idempotencyKey, payload));
    }

    private void publishShelterCacheRegen(List<SimShelter> shelters) {
        for (SimShelter shelter : shelters) {
            CacheRegenerationRequestedPayload payload = CacheRegenerationRequestedPayload.builder()
                    .cacheKey("shelter:status:" + shelter.getShelterId())
                    .cacheKeyFamily("shelter_status")
                    .requestedAt(OffsetDateTime.now())
                    .reason("simulator_bulk_entry")
                    .schemaVersion(1)
                    .build();

            String idempotencyKey = "cache-regen:sim:shelter:" + shelter.getShelterId()
                    + ":" + OffsetDateTime.now().toEpochSecond();
            eventPublisher.publish(EventEnvelope.of("CacheRegenerationRequested", idempotencyKey, payload));
        }
    }
}
