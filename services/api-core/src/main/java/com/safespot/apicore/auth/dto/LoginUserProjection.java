package com.safespot.apicore.auth.dto;

import com.safespot.apicore.domain.enums.Role;

public interface LoginUserProjection {
    Long getUserId();

    String getUsername();

    String getPasswordHash();

    String getName();

    Role getRole();

    boolean isActive();
}
