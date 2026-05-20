package com.safespot.apicore.admin.controller;

import com.safespot.apicore.admin.dto.AdminLogPageResponse;
import com.safespot.apicore.admin.service.AdminLogService;
import com.safespot.apicore.common.ApiResponse;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/logs")
@RequiredArgsConstructor
public class AdminLogController {

    private final AdminLogService adminLogService;
    private final ApiCoreMetrics metrics;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminLogPageResponse>> listLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        AdminLogPageResponse response =
                adminLogService.listLogs(action, targetType, from, to, page, size);

        metrics.incAdminApiCall("GET", "/admin/logs", "200");
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
