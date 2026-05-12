package com.safespot.apicore.auth;

import com.safespot.apicore.auth.dto.LoginRequest;
import com.safespot.apicore.auth.dto.LoginResponse;
import com.safespot.apicore.auth.dto.LoginUserProjection;
import com.safespot.apicore.auth.service.AuthService;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.enums.Role;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.repository.AppUserRepository;
import com.safespot.apicore.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private ApiCoreMetrics metrics;

    @InjectMocks
    private AuthService authService;

    private LoginUserProjection activeAdmin;

    @BeforeEach
    void setUp() {
        activeAdmin = new TestLoginUserProjection(
                1L,
                "admin01",
                "$2a$10$hash",
                "홍길동",
                Role.ADMIN,
                true);
    }

    @Test
    void login_success() {
        when(appUserRepository.findProjectedByUsername("admin01")).thenReturn(Optional.of(activeAdmin));
        when(passwordEncoder.matches("P@ssw0rd!", "$2a$10$hash")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "admin01", Role.ADMIN)).thenReturn("jwt-token");
        when(jwtTokenProvider.getExpirationSeconds()).thenReturn(1800L);

        LoginRequest req = new LoginRequest();
        setField(req, "loginId", "admin01");
        setField(req, "password", "P@ssw0rd!");

        LoginResponse response = authService.login(req);

        assertThat(response.getAccessToken()).isEqualTo("jwt-token");
        assertThat(response.getExpiresIn()).isEqualTo(1800L);
        assertThat(response.getUser().getUsername()).isEqualTo("admin01");
        assertThat(response.getUser().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void login_invalidCredentials_wrongPassword() {
        when(appUserRepository.findProjectedByUsername("admin01")).thenReturn(Optional.of(activeAdmin));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        LoginRequest req = new LoginRequest();
        setField(req, "loginId", "admin01");
        setField(req, "password", "wrong");

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void login_invalidCredentials_userNotFound() {
        when(appUserRepository.findProjectedByUsername("unknown")).thenReturn(Optional.empty());

        LoginRequest req = new LoginRequest();
        setField(req, "loginId", "unknown");
        setField(req, "password", "pass");

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void login_accountDisabled() {
        LoginUserProjection disabled = new TestLoginUserProjection(
                2L,
                "disabled",
                "hash",
                "비활성",
                Role.USER,
                false);
        when(appUserRepository.findProjectedByUsername("disabled")).thenReturn(Optional.of(disabled));

        LoginRequest req = new LoginRequest();
        setField(req, "loginId", "disabled");
        setField(req, "password", "pass");

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("ACCOUNT_DISABLED"));
    }

    private void setField(Object obj, String fieldName, String value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record TestLoginUserProjection(
            Long userId,
            String username,
            String passwordHash,
            String name,
            Role role,
            boolean active) implements LoginUserProjection {
        @Override
        public Long getUserId() {
            return userId;
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public String getPasswordHash() {
            return passwordHash;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Role getRole() {
            return role;
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
