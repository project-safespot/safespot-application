package com.safespot.apicore.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.apicore.admin.dto.*;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.*;
import com.safespot.apicore.domain.enums.*;
import com.safespot.apicore.event.EventEnvelope;
import com.safespot.apicore.event.payload.*;
import com.safespot.apicore.event.springevent.*;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    public List<EvacuationEntryItem> listEntries(Long shelterId, String status) {
        shelterRepository.findById(shelterId)
                .orElseThrow(() -> ApiException.notFound("대피소를 찾을 수 없습니다."));

        List<EvacuationEntry> entries;
        if (status != null) {
            EntryStatus entryStatus = parseEntryStatus(status);
            entries = entryRepository.findByShelterIdAndEntryStatus(shelterId, entryStatus);
        } else {
            entries = entryRepository.findByShelterId(shelterId);
        }

        return entries.stream().map(e -> {
            EntryDetail detail = entryDetailRepository.findByEntryId(e.getEntryId()).orElse(null);
            return buildItem(e, detail);
        }).toList();
    }

    @Transactional
    public CreateEntryResponse createEntry(CreateEntryRequest request, Long adminId, String ipAddress) {
        Shelter shelter = shelterRepository.findById(request.getShelterId())
                .orElseThrow(() -> ApiException.notFound("대피소를 찾을 수 없습니다."));

        if (request.getAlertId() != null) {
            disasterAlertRepository.findById(request.getAlertId())
                    .orElseThrow(() -> ApiException.notFound("재난 알림을 찾을 수 없습니다."));
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
                        "entry:" + entryId + ":ENTERED:v1",
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
                .orElseThrow(() -> ApiException.notFound("입소 기록을 찾을 수 없습니다."));

        if (entry.getEntryStatus() == EntryStatus.EXITED) {
            throw ApiException.conflict("ALREADY_EXITED", "이미 퇴소 처리된 입소자입니다.");
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
                        "entry:" + entryId + ":EXITED:v1",
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
                .orElseThrow(() -> ApiException.notFound("입소 기록을 찾을 수 없습니다."));

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

    private EvacuationEntryItem buildItem(EvacuationEntry e, EntryDetail detail) {
        EvacuationEntryItem.Detail detailDto = null;
        if (detail != null) {
            detailDto = EvacuationEntryItem.Detail.builder()
                    .address(e.getAddress())
                    .familyInfo(detail.getFamilyInfo())
                    .healthStatus(detail.getHealthStatus().name())
                    .specialProtectionFlag(detail.getSpecialProtectionFlag())
                    .build();
        }
        return EvacuationEntryItem.builder()
                .entryId(e.getEntryId())
                .shelterId(e.getShelterId())
                .alertId(e.getAlertId())
                .userId(e.getUserId())
                .visitorName(e.getVisitorName())
                .visitorPhone(e.getVisitorPhone())
                .entryStatus(e.getEntryStatus().name())
                .enteredAt(e.getEnteredAt())
                .exitedAt(e.getExitedAt())
                .note(e.getNote())
                .detail(detailDto)
                .build();
    }

    private EntryStatus parseEntryStatus(String status) {
        try {
            return EntryStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "status 값이 올바르지 않습니다.");
        }
    }

    private HealthStatus parseHealthStatus(String value) {
        if (value == null) return HealthStatus.정상;
        try {
            return HealthStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "healthStatus 값이 올바르지 않습니다. 허용값: 정상, 부상, 응급, 기타");
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
