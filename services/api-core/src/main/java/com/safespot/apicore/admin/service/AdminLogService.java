package com.safespot.apicore.admin.service;

import com.safespot.apicore.admin.dto.AdminLogPageResponse;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AdminAuditLog;
import com.safespot.apicore.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminLogService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final AdminAuditLogRepository adminAuditLogRepository;

    @Transactional(readOnly = true)
    public AdminLogPageResponse listLogs(
            String action,
            String targetType,
            String from,
            String to,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        OffsetDateTime fromDateTime = parseStartDate(from);
        OffsetDateTime toDateTime = parseEndExclusiveDate(to);

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<AdminAuditLog> result = adminAuditLogRepository.searchLogs(
                normalizeBlank(action),
                normalizeBlank(targetType),
                fromDateTime,
                toDateTime,
                pageable
        );

        List<AdminLogPageResponse.AdminLogItem> items = result.getContent().stream()
                .map(this::toItem)
                .toList();

        return AdminLogPageResponse.builder()
                .items(items)
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .build();
    }

    private AdminLogPageResponse.AdminLogItem toItem(AdminAuditLog log) {
        String actionLabel = actionLabel(log.getAction());
        String targetLabel = targetLabel(log.getTargetType());

        return AdminLogPageResponse.AdminLogItem.builder()
                .logId(log.getLogId())
                .createdAt(log.getCreatedAt())
                .adminId(log.getAdminId())
                .action(log.getAction())
                .actionLabel(actionLabel)
                .targetType(log.getTargetType())
                .targetLabel(targetLabel)
                .targetId(log.getTargetId())
                .ipAddress(log.getIpAddress())
                .summary(buildSummary(log.getAction(), log.getTargetType(), log.getTargetId()))
                .payloadBefore(log.getPayloadBefore())
                .payloadAfter(log.getPayloadAfter())
                .build();
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime parseStartDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            return date.atStartOfDay(SEOUL_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "from 날짜 형식이 올바르지 않습니다.");
        }
    }

    private OffsetDateTime parseEndExclusiveDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim()).plusDays(1);
            return date.atStartOfDay(SEOUL_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "to 날짜 형식이 올바르지 않습니다.");
        }
    }

    private String actionLabel(String action) {
        if (action == null) {
            return "-";
        }
        return switch (action) {
            case "ENTRY_CREATE" -> "입소자 등록";
            case "ENTRY_UPDATE" -> "입소자 수정";
            case "ENTRY_EXIT" -> "퇴소 처리";
            case "SHELTER_UPDATE" -> "대피소 정보 수정";
            default -> action;
        };
    }

    private String targetLabel(String targetType) {
        if (targetType == null) {
            return "-";
        }
        return switch (targetType) {
            case "evacuation_entry" -> "입소자";
            case "shelter" -> "대피소";
            default -> targetType;
        };
    }

    private String buildSummary(String action, String targetType, Long targetId) {
        String label = actionLabel(action);
        String target = targetLabel(targetType);
        if (targetId == null) {
            return label;
        }
        return label + " - " + target + " ID " + targetId;
    }
}
