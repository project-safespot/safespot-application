package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.service.ShelterReadService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Validated
public class ShelterController {

    private final ShelterReadService shelterReadService;

    @GetMapping("/shelters/nearby")
    public ResponseEntity<ApiResponse<Map<String, List<ShelterNearbyItem>>>> getNearby(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) String disasterType
    ) {
        if (lat == null || lng == null || radius == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "lat, lng, radius는 필수입니다.");
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "lat/lng 범위를 초과했습니다.");
        }
        if (lat < 37.41 || lat > 37.71 || lng < 126.73 || lng > 127.19) {
            throw new ApiException(ErrorCode.UNSUPPORTED_REGION, "현재 서울 지역만 지원합니다.");
        }
        if (radius < 100 || radius > 5000) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "radius는 100~5000 사이여야 합니다.");
        }
        if (disasterType != null && !isValidDisasterType(disasterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "disasterType 값이 올바르지 않습니다.");
        }

        List<ShelterNearbyItem> items = shelterReadService.findNearby(lat, lng, radius, disasterType);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("items", items)));
    }

    @GetMapping("/shelters/{shelterId}")
    public ResponseEntity<ApiResponse<ShelterDetailDto>> getById(@PathVariable Long shelterId) {
        ShelterDetailDto dto = shelterReadService.findById(shelterId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    private boolean isValidDisasterType(String value) {
        return "EARTHQUAKE".equals(value) || "FLOOD".equals(value) || "LANDSLIDE".equals(value);
    }
}
