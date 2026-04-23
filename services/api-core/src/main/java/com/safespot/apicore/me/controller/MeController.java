package com.safespot.apicore.me.controller;

import com.safespot.apicore.common.ApiResponse;
import com.safespot.apicore.common.exception.ApiException;
import com.safespot.apicore.domain.entity.AppUser;
import com.safespot.apicore.me.dto.MeResponse;
import com.safespot.apicore.repository.AppUserRepository;
import com.safespot.apicore.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MeController {

    private final AppUserRepository appUserRepository;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> getMe(@AuthenticationPrincipal UserPrincipal principal) {
        AppUser user = appUserRepository.findById(principal.getUserId())
                .orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));

        MeResponse response = MeResponse.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .name(user.getName())
                .phoneNumber(user.getPhone())
                .role(user.getRole().name())
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
