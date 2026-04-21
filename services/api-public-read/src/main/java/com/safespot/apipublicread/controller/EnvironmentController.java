package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.service.EnvironmentReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
public class EnvironmentController {

    private final EnvironmentReadService environmentReadService;

    @GetMapping("/weather-alerts")
    public ResponseEntity<ApiResponse<WeatherAlertDto>> getWeather(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Integer nx,
            @RequestParam(required = false) Integer ny
    ) {
        if (region == null && nx == null && ny == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, nx, ny 중 최소 1개는 필요합니다.");
        }
        if ((nx != null && ny == null) || (nx == null && ny != null)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "nx와 ny는 함께 제공해야 합니다.");
        }

        WeatherAlertDto dto = environmentReadService.findWeather(region, nx, ny);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @GetMapping("/air-quality")
    public ResponseEntity<ApiResponse<AirQualityDto>> getAirQuality(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String stationName
    ) {
        if (region == null && stationName == null) {
            throw new ApiException(ErrorCode.MISSING_REQUIRED_FIELD, "region, stationName 중 최소 1개는 필요합니다.");
        }

        AirQualityDto dto = environmentReadService.findAirQuality(region, stationName);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
