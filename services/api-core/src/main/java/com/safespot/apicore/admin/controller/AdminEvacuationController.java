package com.safespot.apicore.admin.controller;

import com.safespot.apicore.admin.dto.*;
import com.safespot.apicore.admin.service.EvacuationService;
import com.safespot.apicore.common.ApiResponse;
import com.safespot.apicore.metrics.ApiCoreMetrics;
import com.safespot.apicore.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/evacuation-entries")
@RequiredArgsConstructor
public class AdminEvacuationController {

    private final EvacuationService evacuationService;
    private final ApiCoreMetrics metrics;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, List<EvacuationEntryItem>>>> listEntries(
            @RequestParam Long shelterId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal principal) {
        List<EvacuationEntryItem> items = evacuationService.listEntries(shelterId, status);
        metrics.incAdminApiCall("GET", "/admin/evacuation-entries", "200");
        return ResponseEntity.ok(ApiResponse.ok(Map.of("items", items)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateEntryResponse>> createEntry(
            @Valid @RequestBody CreateEntryRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        CreateEntryResponse response = evacuationService.createEntry(request, principal.getUserId(), ip);
        metrics.incAdminApiCall("POST", "/admin/evacuation-entries", "201");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/{entryId}/exit")
    public ResponseEntity<ApiResponse<ExitEntryResponse>> exitEntry(
            @PathVariable Long entryId,
            @Valid @RequestBody(required = false) ExitEntryRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        ExitEntryResponse response = evacuationService.exitEntry(entryId, request, principal.getUserId(), ip);
        metrics.incAdminApiCall("POST", "/admin/evacuation-entries/{entryId}/exit", "200");
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{entryId}")
    public ResponseEntity<ApiResponse<UpdateEntryResponse>> updateEntry(
            @PathVariable Long entryId,
            @Valid @RequestBody UpdateEntryRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        UpdateEntryResponse response = evacuationService.updateEntry(entryId, request, principal.getUserId(), ip);
        metrics.incAdminApiCall("PATCH", "/admin/evacuation-entries/{entryId}", "200");
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
