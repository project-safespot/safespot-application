package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Builder
public class AdminLogPageResponse {

    private final List<AdminLogItem> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean hasNext;

    @Getter
    @Builder
    public static class AdminLogItem {
        private final Long logId;
        private final OffsetDateTime createdAt;
        private final Long adminId;
        private final String action;
        private final String actionLabel;
        private final String targetType;
        private final String targetLabel;
        private final Long targetId;
        private final String ipAddress;
        private final String summary;
        private final String payloadBefore;
        private final String payloadAfter;
    }
}
