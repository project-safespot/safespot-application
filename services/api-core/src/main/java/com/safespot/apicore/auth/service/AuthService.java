package com.safespot.apicore.auth.service;

import com.safespot.apicore.auth.dto.LoginRequest;
import com.safespot.apicore.auth.dto.LoginResponse;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.AppUserRepository;
import com.safespot.apicore.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApiCoreMetrics metrics;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        AppUser user = appUserRepository.findByUsername(request.getLoginId()).orElse(null);

        if (user == null) {
            metrics.incAuthLogin("failure", "invalid_credentials");
            throw ApiException.unauthorized("INVALID_CREDENTIALS");
        }

        if (!user.isActive()) {
            metrics.incAuthLogin("failure", "account_disabled");
            throw ApiException.unauthorized("ACCOUNT_DISABLED");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            metrics.incAuthLogin("failure", "invalid_credentials");
            throw ApiException.unauthorized("INVALID_CREDENTIALS");
        }

        String token = jwtTokenProvider.generateToken(user.getUserId(), user.getUsername(), user.getRole());

        metrics.incAuthLogin("success", "none");

        return LoginResponse.builder()
                .accessToken(token)
                .expiresIn(jwtTokenProvider.getExpirationSeconds())
                .user(LoginResponse.UserInfo.builder()
                        .userId(user.getUserId())
                        .username(user.getUsername())
                        .name(user.getName())
                        .role(user.getRole().name())
                        .build())
                .build();
    }
}
