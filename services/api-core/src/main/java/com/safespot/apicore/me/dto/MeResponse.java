package com.safespot.apicore.me.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class MeResponse {
    private final Long userId;
    private final String username;
    private final String name;
    private final String phoneNumber;
    private final String role;
    private final boolean isActive;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;
}
