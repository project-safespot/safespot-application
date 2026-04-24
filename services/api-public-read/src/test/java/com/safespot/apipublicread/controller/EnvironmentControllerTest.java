package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.dto.AirQualityDto;
import com.safespot.apipublicread.dto.ApiResponse;
import com.safespot.apipublicread.dto.WeatherAlertDto;
import com.safespot.apipublicread.exception.GlobalExceptionHandler;
import com.safespot.apipublicread.service.EnvironmentReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EnvironmentController.class)
@Import(GlobalExceptionHandler.class)
class EnvironmentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean EnvironmentReadService environmentReadService;
    @MockitoBean RegionToGridResolver regionToGridResolver;

    @BeforeEach
    void stubSeoulDefaults() {
        lenient().when(regionToGridResolver.isSupported("서울특별시")).thenReturn(true);
        lenient().when(regionToGridResolver.isSupported("서울")).thenReturn(true);
        lenient().when(regionToGridResolver.isSupported("부산광역시")).thenReturn(false);
        lenient().when(regionToGridResolver.isSupportedGrid(60, 127)).thenReturn(true);
        lenient().when(regionToGridResolver.isSupportedGrid(10, 10)).thenReturn(false);
    }

    @Test
    void getWeather_withNxNy_success() throws Exception {
        WeatherAlertDto dto = new WeatherAlertDto("서울특별시", 60, 127, 18.5, "맑음", "2026-04-15T15:00:00+09:00");
        when(environmentReadService.findWeather(any(), eq(60), eq(127))).thenReturn(dto);

        mockMvc.perform(get("/weather-alerts").param("nx", "60").param("ny", "127"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temperature").value(18.5))
                .andExpect(jsonPath("$.data.nx").value(60));
    }

    @Test
    void getWeather_nonSeoulRegion_returns400() throws Exception {
        mockMvc.perform(get("/weather-alerts").param("region", "부산광역시"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_REGION"));
    }

    @Test
    void getWeather_nonSeoulGrid_returns400() throws Exception {
        mockMvc.perform(get("/weather-alerts").param("nx", "10").param("ny", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_REGION"));
    }

    @Test
    void getWeather_noData_returnsNullData() throws Exception {
        when(environmentReadService.findWeather(any(), any(), any())).thenReturn(null);

        mockMvc.perform(get("/weather-alerts").param("region", "서울특별시"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getWeather_allMissing_returns400() throws Exception {
        mockMvc.perform(get("/weather-alerts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void getWeather_onlyNx_returns400() throws Exception {
        mockMvc.perform(get("/weather-alerts").param("nx", "60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getAirQuality_success() throws Exception {
        AirQualityDto dto = new AirQualityDto("종로구", 42, "좋음", "2026-04-15T15:00:00+09:00");
        when(environmentReadService.findAirQuality(any(), eq("종로구"))).thenReturn(dto);

        mockMvc.perform(get("/air-quality").param("stationName", "종로구"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationName").value("종로구"))
                .andExpect(jsonPath("$.data.aqi").value(42));
    }

    @Test
    void getAirQuality_noData_returnsNullData() throws Exception {
        when(environmentReadService.findAirQuality(any(), any())).thenReturn(null);

        mockMvc.perform(get("/air-quality").param("region", "서울특별시"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getAirQuality_allMissing_returns400() throws Exception {
        mockMvc.perform(get("/air-quality"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }
}
