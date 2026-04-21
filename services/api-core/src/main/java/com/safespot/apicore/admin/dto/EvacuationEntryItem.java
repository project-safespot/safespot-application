package com.safespot.apicore.admin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class EvacuationEntryItem {

    private final Long entryId;
    private final Long shelterId;
    private final Long alertId;
    private final Long userId;
    private final String visitorName;
    private final String visitorPhone;
    private final String entryStatus;
    private final OffsetDateTime enteredAt;
    private final OffsetDateTime exitedAt;
    private final String note;
    private final Detail detail;

    @Getter
    @Builder
    public static class Detail {
        private final String address;
        private final String familyInfo;
        private final String healthStatus;
        private final Boolean specialProtectionFlag;
    }
}
