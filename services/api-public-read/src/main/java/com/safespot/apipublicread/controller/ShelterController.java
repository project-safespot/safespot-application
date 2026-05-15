package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterMapTilesResponse;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.service.ShelterReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(required = false) String disasterType,
            @RequestParam(required = false) String shelterType,
            @RequestParam(required = false) Integer limit
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
        if (limit != null && limit > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit은 50 이하여야 합니다.");
        }
        if (disasterType != null && !isValidDisasterType(disasterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "disasterType 값이 올바르지 않습니다.");
        }
        if (shelterType != null && !isValidShelterType(shelterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "shelterType 값이 올바르지 않습니다.");
        }

        List<ShelterNearbyItem> items = shelterReadService.findNearby(lat, lng, radius, disasterType, shelterType, limit);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("items", items)));
    }

    @GetMapping("/shelters/map/tiles")
    public ResponseEntity<ApiResponse<ShelterMapTilesResponse>> getMapTiles(
            @RequestParam(required = false) Integer z,
            @RequestParam(required = false) String tiles,
            @RequestParam(required = false) String disasterType,
            @RequestParam(required = false) String shelterType
    ) {
        if (z == null || tiles == null || tiles.isBlank()) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "z, tiles는 필수입니다.");
        }
        if (z < 11 || z > 16) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "z는 11~16 사이여야 합니다.");
        }
        if (disasterType != null && !isValidDisasterType(disasterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "disasterType 값이 올바르지 않습니다.");
        }
        if (shelterType != null && !isValidShelterType(shelterType)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "shelterType 값이 올바르지 않습니다.");
        }

        ShelterMapTilesResponse response = shelterReadService.findMapTiles(
                z,
                List.of(tiles.split(",")),
                disasterType,
                shelterType
        );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/shelters/{shelterId}")
    public ResponseEntity<ApiResponse<ShelterDetailDto>> getById(@PathVariable Long shelterId) {
        ShelterDetailDto dto = shelterReadService.findById(shelterId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    private boolean isValidDisasterType(String value) {
        return "EARTHQUAKE".equals(value) || "FLOOD".equals(value) || "LANDSLIDE".equals(value);
    }

    private boolean isValidShelterType(String value) {
        return "DESIGNATED".equals(value) || "TEMPORARY".equals(value) || "WIDE".equals(value);
    }
}
