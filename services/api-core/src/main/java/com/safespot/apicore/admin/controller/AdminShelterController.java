package com.safespot.apicore.admin.controller;

import com.safespot.apicore.admin.dto.UpdateShelterRequest;
import com.safespot.apicore.admin.dto.UpdateShelterResponse;
import com.safespot.apicore.admin.service.ShelterAdminService;
import com.safespot.apicore.common.ApiResponse;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/shelters")
@RequiredArgsConstructor
public class AdminShelterController {

    private final ShelterAdminService shelterAdminService;
    private final ApiCoreMetrics metrics;

    @PatchMapping("/{shelterId}")
    public ResponseEntity<ApiResponse<UpdateShelterResponse>> updateShelter(
            @PathVariable Long shelterId,
            @Valid @RequestBody UpdateShelterRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        UpdateShelterResponse response = shelterAdminService.updateShelter(
                shelterId, request, principal.getUserId(), ip);
        metrics.incAdminApiCall("PATCH", "/admin/shelters/{shelterId}", "200");
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
