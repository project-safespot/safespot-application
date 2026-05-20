package com.safespot.apicore.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.admin.dto.CreateEntryRequest;
import com.safespot.apicore.admin.dto.CreateEntryResponse;
import com.safespot.apicore.admin.dto.EvacuationEntryItem;
import com.safespot.apicore.admin.dto.EvacuationEntryPageResponse;
import com.safespot.apicore.admin.dto.ExitEntryRequest;
import com.safespot.apicore.admin.dto.ExitEntryResponse;
import com.safespot.apicore.admin.dto.UpdateEntryRequest;
import com.safespot.apicore.admin.dto.UpdateEntryResponse;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.domain.entity.EntryDetail;
import com.safespot.apicore.domain.entity.EvacuationEntry;
import com.safespot.apicore.domain.entity.EvacuationEventHistory;
import com.safespot.apicore.domain.entity.Shelter;
import com.safespot.apicore.domain.enums.EntryStatus;
import com.safespot.apicore.domain.enums.EventHistoryType;
import com.safespot.apicore.domain.enums.HealthStatus;
import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.EvacuationEntryCreatedPayload;
import com.safespot.apicore.event.payload.EvacuationEntryExitedPayload;
import com.safespot.apicore.event.payload.EvacuationEntryUpdatedPayload;
import com.safespot.apicore.event.springevent.EvacuationEntryCreatedSpringEvent;
import com.safespot.apicore.event.springevent.EvacuationEntryExitedSpringEvent;
import com.safespot.apicore.event.springevent.EvacuationEntryUpdatedSpringEvent;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.AdminAuditLogRepository;
import com.safespot.apicore.repository.DisasterAlertRepository;
import com.safespot.apicore.repository.EntryDetailRepository;
import com.safespot.apicore.repository.EvacuationEntryRepository;
import com.safespot.apicore.repository.EvacuationEventHistoryRepository;
import com.safespot.apicore.repository.ShelterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvacuationService {

    private final EvacuationEntryRepository entryRepository;
    private final EntryDetailRepository entryDetailRepository;
    private final ShelterRepository shelterRepository;
    private final DisasterAlertRepository disasterAlertRepository;
    private final EvacuationEventHistoryRepository historyRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ApiCoreMetrics metrics;

    @Transactional(readOnly = true)
    public EvacuationEntryPageResponse listEntries(
            Long shelterId,
            String status,
            String keyword,
            Boolean specialOnly,
            int page,
            int size
    ) {
        if (shelterId != null) {
            shelterRepository.findById(shelterId)
                    .orElseThrow(() -> ApiException.notFound("Shelter not found."));
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        EntryStatus entryStatus = status != null && !status.isBlank()
                ? parseEntryStatus(status)
                : EntryStatus.ENTERED;
        String normalizedKeyword = keyword != null ? keyword.trim() : null;

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "enteredAt")
        );

        Page<EvacuationEntryRepository.EvacuationEntryListRow> result = entryRepository.searchAdminEntries(
                shelterId,
                entryStatus,
                normalizedKeyword,
                specialOnly,
                pageable
        );

        List<EvacuationEntryItem> items = result.getContent().stream()
                .map(this::buildItemFromRow)
                .toList();

        return EvacuationEntryPageResponse.builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .build();
    }

    @Transactional
    public CreateEntryResponse createEntry(CreateEntryRequest request, Long adminId, String ipAddress) {
        Shelter shelter = shelterRepository.findById(request.getShelterId())
                .orElseThrow(() -> ApiException.notFound("Shelter not found."));

        if (request.getAlertId() != null) {
            disasterAlertRepository.findById(request.getAlertId())
                    .orElseThrow(() -> ApiException.notFound("Alert not found."));
        }

        EvacuationEntry entry = EvacuationEntry.builder()
                .shelterId(request.getShelterId())
                .alertId(request.getAlertId())
                .userId(request.getUserId())
                .visitorName(request.getName())
                .visitorPhone(request.getPhoneNumber())
                .address(request.getAddress())
                .note(request.getNote())
                .entryStatus(EntryStatus.ENTERED)
                .enteredAt(OffsetDateTime.now())
                .build();
        entry = entryRepository.save(entry);

        HealthStatus healthStatus = parseHealthStatus(request.getHealthStatus());
        EntryDetail detail = EntryDetail.builder()
                .entryId(entry.getEntryId())
                .familyInfo(request.getFamilyInfo())
                .healthStatus(healthStatus)
                .specialProtectionFlag(request.getSpecialProtectionFlag() != null
                        ? request.getSpecialProtectionFlag() : false)
                .build();
        entryDetailRepository.save(detail);

        historyRepository.save(EvacuationEventHistory.builder()
                .entryId(entry.getEntryId())
                .shelterId(entry.getShelterId())
                .eventType(EventHistoryType.CHECK_IN)
                .prevStatus(null)
                .nextStatus(EntryStatus.ENTERED.name())
                .recordedBy(adminId)
                .build());

        auditLogRepository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .action("ENTRY_CREATE")
                .targetType("evacuation_entry")
                .targetId(entry.getEntryId())
                .payloadBefore(null)
                .payloadAfter(toJson(Map.of(
                        "entryId", entry.getEntryId(),
                        "shelterId", entry.getShelterId(),
                        "entryStatus", EntryStatus.ENTERED.name())))
                .ipAddress(ipAddress)
                .build());

        metrics.incAdminAction("ENTRY_CREATE");
        metrics.incCheckin();

        final Long entryId = entry.getEntryId();
        final OffsetDateTime enteredAt = entry.getEnteredAt();
        eventPublisher.publishEvent(new EvacuationEntryCreatedSpringEvent(this,
                EventEnvelope.of("EvacuationEntryCreated",
                        "entry:" + entryId + ":ENTERED",
                        EvacuationEntryCreatedPayload.builder()
                                .entryId(entryId)
                                .shelterId(entry.getShelterId())
                                .nextStatus("ENTERED")
                                .recordedByAdminId(adminId)
                                .enteredAt(enteredAt)
                                .build())));

        return CreateEntryResponse.builder()
                .entryId(entryId)
                .shelterId(entry.getShelterId())
                .entryStatus(EntryStatus.ENTERED.name())
                .enteredAt(enteredAt)
                .build();
    }

    @Transactional
    public ExitEntryResponse exitEntry(Long entryId, ExitEntryRequest request, Long adminId, String ipAddress) {
        EvacuationEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("Entry not found."));

        if (entry.getEntryStatus() == EntryStatus.EXITED) {
            throw ApiException.conflict("ALREADY_EXITED", "Entry is already exited.");
        }

        String beforeStatus = entry.getEntryStatus().name();
        entry.exit();
        entryRepository.save(entry);

        historyRepository.save(EvacuationEventHistory.builder()
                .entryId(entry.getEntryId())
                .shelterId(entry.getShelterId())
                .eventType(EventHistoryType.CHECK_OUT)
                .prevStatus(beforeStatus)
                .nextStatus(EntryStatus.EXITED.name())
                .recordedBy(adminId)
                .remark(request != null ? request.getReason() : null)
                .build());

        auditLogRepository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .action("ENTRY_EXIT")
                .targetType("evacuation_entry")
                .targetId(entryId)
                .payloadBefore(toJson(Map.of("entryStatus", beforeStatus)))
                .payloadAfter(toJson(buildAuditAfter(
                        Map.of("entryStatus", EntryStatus.EXITED.name(),
                                "exitedAt", entry.getExitedAt().toString()),
                        request != null ? request.getReason() : null)))
                .ipAddress(ipAddress)
                .build());

        metrics.incAdminAction("ENTRY_EXIT");
        metrics.incCheckout();

        eventPublisher.publishEvent(new EvacuationEntryExitedSpringEvent(this,
                EventEnvelope.of("EvacuationEntryExited",
                        "entry:" + entryId + ":EXITED",
                        EvacuationEntryExitedPayload.builder()
                                .entryId(entryId)
                                .shelterId(entry.getShelterId())
                                .nextStatus("EXITED")
                                .recordedByAdminId(adminId)
                                .exitedAt(entry.getExitedAt())
                                .build())));

        return ExitEntryResponse.builder()
                .entryId(entryId)
                .entryStatus(EntryStatus.EXITED.name())
                .exitedAt(entry.getExitedAt())
                .build();
    }

    @Transactional
    public UpdateEntryResponse updateEntry(Long entryId, UpdateEntryRequest request, Long adminId, String ipAddress) {
        EvacuationEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> ApiException.notFound("Entry not found."));

        EntryDetail detail = entryDetailRepository.findByEntryId(entryId).orElse(null);

        List<String> changedFields = new ArrayList<>();

        String beforeJson = toJson(Map.of(
                "address", nullToEmpty(entry.getAddress()),
                "note", nullToEmpty(entry.getNote()),
                "familyInfo", detail != null ? nullToEmpty(detail.getFamilyInfo()) : "",
                "healthStatus", detail != null ? detail.getHealthStatus().name() : "",
                "specialProtectionFlag", detail != null ? detail.getSpecialProtectionFlag() : false));

        if (request.getAddress() != null) {
            entry.updateInfo(request.getAddress(), null);
            changedFields.add("address");
        }
        if (request.getNote() != null) {
            entry.updateInfo(null, request.getNote());
            changedFields.add("note");
        }
        entryRepository.save(entry);

        if (detail != null) {
            HealthStatus hs = request.getHealthStatus() != null
                    ? parseHealthStatus(request.getHealthStatus())
                    : detail.getHealthStatus();
            if (request.getFamilyInfo() != null) changedFields.add("familyInfo");
            if (request.getHealthStatus() != null) changedFields.add("healthStatus");
            if (request.getSpecialProtectionFlag() != null) changedFields.add("specialProtectionFlag");
            detail.update(request.getFamilyInfo(), hs, request.getSpecialProtectionFlag());
            entryDetailRepository.save(detail);
        }

        OffsetDateTime updatedAt = OffsetDateTime.now();

        auditLogRepository.save(AdminAuditLog.builder()
                .adminId(adminId)
                .action("ENTRY_UPDATE")
                .targetType("evacuation_entry")
                .targetId(entryId)
                .payloadBefore(beforeJson)
                .payloadAfter(toJson(buildAuditAfter(
                        Map.of("changedFields", changedFields),
                        request.getReason())))
                .ipAddress(ipAddress)
                .build());

        historyRepository.save(EvacuationEventHistory.builder()
                .entryId(entry.getEntryId())
                .shelterId(entry.getShelterId())
                .eventType(EventHistoryType.STATUS_UPDATE)
                .prevStatus(entry.getEntryStatus().name())
                .nextStatus(entry.getEntryStatus().name())
                .recordedBy(adminId)
                .remark(request.getReason())
                .build());

        metrics.incAdminAction("ENTRY_UPDATE");

        eventPublisher.publishEvent(new EvacuationEntryUpdatedSpringEvent(this,
                EventEnvelope.ofWithEventId("EvacuationEntryUpdated",
                        "entry:" + entryId + ":UPDATED:",
                        EvacuationEntryUpdatedPayload.builder()
                                .entryId(entryId)
                                .shelterId(entry.getShelterId())
                                .recordedByAdminId(adminId)
                                .updatedAt(updatedAt)
                                .changedFields(changedFields)
                                .build())));

        return UpdateEntryResponse.builder()
                .entryId(entryId)
                .updatedAt(updatedAt)
                .build();
    }

    private EvacuationEntryItem buildItemFromRow(EvacuationEntryRepository.EvacuationEntryListRow row) {
        EvacuationEntryItem.Detail detailDto = EvacuationEntryItem.Detail.builder()
                .address(row.getAddress())
                .familyInfo(row.getFamilyInfo())
                .healthStatus(row.getHealthStatus() != null ? row.getHealthStatus().name() : null)
                .specialProtectionFlag(Boolean.TRUE.equals(row.getSpecialProtectionFlag()))
                .build();

        return EvacuationEntryItem.builder()
                .entryId(row.getEntryId())
                .shelterId(row.getShelterId())
                .shelterName(row.getShelterName())
                .alertId(row.getAlertId())
                .userId(row.getUserId())
                .visitorName(row.getVisitorName())
                .visitorPhone(row.getVisitorPhone())
                .entryStatus(row.getEntryStatus() != null ? row.getEntryStatus().name() : null)
                .enteredAt(row.getEnteredAt())
                .exitedAt(row.getExitedAt())
                .note(row.getNote())
                .detail(detailDto)
                .build();
    }

    private EntryStatus parseEntryStatus(String status) {
        try {
            return EntryStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Invalid status value.");
        }
    }

    private HealthStatus parseHealthStatus(String value) {
        if (value == null) return HealthStatus.values()[0];
        try {
            return HealthStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Invalid healthStatus value.");
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private Map<String, Object> buildAuditAfter(Map<String, Object> data, String reason) {
        java.util.Map<String, Object> result = new java.util.HashMap<>(data);
        if (reason != null) {
            result.put("auditMeta", Map.of("reason", reason));
        }
        return result;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
