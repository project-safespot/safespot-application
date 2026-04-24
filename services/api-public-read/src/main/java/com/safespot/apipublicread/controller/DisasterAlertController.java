package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.service.DisasterAlertReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Validated
public class DisasterAlertController {

    private final DisasterAlertReadService disasterAlertReadService;
    private final RegionToGridResolver regionToGridResolver;

    @GetMapping("/disaster-alerts")
    public ResponseEntity<ApiResponse<Map<String, List<DisasterAlertItem>>>> getAlerts(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String disasterType
    ) {
        if (disasterType != null && !isValidDisasterType(disasterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "disasterType 값이 올바르지 않습니다.");
        }
        if (region != null && !regionToGridResolver.isSupported(region)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "현재 서울 지역만 지원합니다.");
        }

        List<DisasterAlertItem> items = disasterAlertReadService.findAlerts(region, disasterType);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("items", items)));
    }

    @GetMapping("/disasters/{disasterType}/latest")
    public ResponseEntity<ApiResponse<DisasterLatestDto>> getLatest(
            @PathVariable String disasterType,
            @RequestParam(required = false) String region
    ) {
        if (!isValidDisasterType(disasterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "disasterType 값이 올바르지 않습니다.");
        }
        if (region == null || region.isBlank()) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region은 필수입니다.");
        }
        if (!regionToGridResolver.isSupported(region)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "현재 서울 지역만 지원합니다.");
        }

        DisasterLatestDto dto = disasterAlertReadService.findLatest(disasterType, region);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    private boolean isValidDisasterType(String value) {
        return "EARTHQUAKE".equals(value) || "FLOOD".equals(value) || "LANDSLIDE".equals(value);
    }
}
