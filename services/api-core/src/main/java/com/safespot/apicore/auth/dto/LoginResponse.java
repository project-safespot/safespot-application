package com.safespot.apicore.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    private final String accessToken;
    private final long expiresIn;
    private final UserInfo user;

    @Getter
    @Builder
    public static class UserInfo {
        private final Long userId;
        private final String username;
        private final String name;
        private final String role;
    }
}
