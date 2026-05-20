package com.safespot.apicore.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.admin.dto.AdminShelterPageResponse;
import com.safespot.apicore.admin.dto.UpdateShelterRequest;
import com.safespot.apicore.admin.dto.UpdateShelterResponse;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.ShelterStatus;
import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.ShelterUpdatedPayload;
import com.safespot.apicore.event.springevent.ShelterUpdatedSpringEvent;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.AdminAuditLogRepository;
import com.safespot.apicore.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ShelterAdminService {

    private final ShelterRepository shelterRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ApiCoreMetrics metrics;

    @Transactional(readOnly = true)
    public AdminShelterPageResponse listShelters(int page, int size, String keyword, String status) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        ShelterStatus shelterStatus = parseShelterStatus(status);
        String normalizedKeyword = keyword != null && !keyword.isBlank() ? keyword.trim() : null;

        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<ShelterRepository.AdminShelterRow> result =
                shelterRepository.searchAdminShelters(normalizedKeyword, shelterStatus, pageable);

        List<AdminShelterPageResponse.ShelterItem> items = result.getContent().stream()
                .map(this::toShelterItem)
                .toList();

        return AdminShelterPageResponse.builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .build();
    }

    @Transactional
    public UpdateShelterResponse updateShelter(Long shelterId, UpdateShelterRequest request,
                                               Long adminId, String ipAddress) {
        Shelter shelter = shelterRepository.findById(shelterId)
                .orElseThrow(() -> ApiException.notFound("대피소를 찾을 수 없습니다."));

        ShelterStatus status = parseShelterStatus(request.getShelterStatus());

        Map<String, Object> before = new HashMap<>();
        before.put("capacity", shelter.getCapacity());
        before.put("shelterStatus", shelter.getShelterStatus().name());
        before.put("manager", shelter.getManager());
        before.put("contact", shelter.getContact());
        before.put("note", shelter.getNote());

        List<String> changedFields = new ArrayList<>();
        if (request.getCapacityTotal() != null) changedFields.add("capacityTotal");
        if (request.getShelterStatus() != null) changedFields.add("shelterStatus");
        if (request.getManager() != null) changedFields.add("manager");
        if (request.getContact() != null) changedFields.add("contact");
        if (request.getNote() != null) changedFields.add("note");

        shelter.update(request.getCapacityTotal(), status, request.getManager(),
                request.getContact(), request.getNote());
        shelterRepository.save(shelter);

        OffsetDateTime updatedAt = shelter.getUpdatedAt();

        Map<String, Object> afterData = new HashMap<>();
        afterData.put("capacity", shelter.getCapacity());
        afterData.put("shelterStatus", shelter.getShelterStatus().name());
        afterData.put("manager", shelter.getManager());
        afterData.put("contact", shelter.getContact());
        afterData.put("note", shelter.getNote());
        afterData.put("auditMeta", Map.of("reason", request.getReason()));

        auditLogRepository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .action("SHELTER_UPDATE")
                .targetType("shelter")
                .targetId(shelterId)
                .payloadBefore(toJson(before))
                .payloadAfter(toJson(afterData))
                .ipAddress(ipAddress)
                .build());

        metrics.incAdminAction("SHELTER_UPDATE");

        eventPublisher.publishEvent(new ShelterUpdatedSpringEvent(this,
                EventEnvelope.ofWithEventId("ShelterUpdated",
                        "shelter:" + shelterId + ":UPDATED:",
                        ShelterUpdatedPayload.builder()
                                .shelterId(shelterId)
                                .recordedByAdminId(adminId)
                                .updatedAt(updatedAt)
                                .changedFields(changedFields)
                                .build())));

        return UpdateShelterResponse.builder()
                .shelterId(shelterId)
                .updatedAt(updatedAt)
                .build();
    }

    private ShelterStatus parseShelterStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ShelterStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "shelterStatus 값이 올바르지 않습니다.");
        }
    }

    private AdminShelterPageResponse.ShelterItem toShelterItem(ShelterRepository.AdminShelterRow row) {
        long currentOccupants = row.getCurrentOccupants() != null ? row.getCurrentOccupants() : 0L;
        int capacity = row.getCapacity() != null ? row.getCapacity() : 0;
        long availableCapacity = Math.max((long) capacity - currentOccupants, 0L);
        double occupancyRate = capacity > 0 ? (currentOccupants * 100.0) / capacity : 0.0;

        return AdminShelterPageResponse.ShelterItem.builder()
                .shelterId(row.getShelterId())
                .name(row.getName())
                .address(row.getAddress())
                .shelterType(row.getShelterType())
                .disasterType(row.getDisasterType() != null ? row.getDisasterType().name() : null)
                .capacity(row.getCapacity())
                .currentOccupants(currentOccupants)
                .availableCapacity(availableCapacity)
                .occupancyRate(occupancyRate)
                .crowdingLevel(resolveCrowdingLevel(occupancyRate))
                .shelterStatus(row.getShelterStatus() != null ? row.getShelterStatus().name() : null)
                .manager(row.getManager())
                .contact(row.getContact())
                .build();
    }

    private String resolveCrowdingLevel(double occupancyRate) {
        if (occupancyRate >= 100.0) {
            return "FULL";
        }
        if (occupancyRate >= 80.0) {
            return "CROWDED";
        }
        if (occupancyRate >= 50.0) {
            return "NORMAL";
        }
        return "AVAILABLE";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
