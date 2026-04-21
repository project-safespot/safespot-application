package com.safespot.apicore.admin.controller;

import com.safespot.apicore.admin.dto.DashboardResponse;
import com.safespot.apicore.admin.service.AdminDashboardService;
import com.safespot.apicore.common.ApiResponse;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;
    private final ApiCoreMetrics metrics;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @AuthenticationPrincipal UserPrincipal principal) {
        metrics.incAdminApiCall("GET", "/admin/dashboard", "200");
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getDashboard()));
    }
}
