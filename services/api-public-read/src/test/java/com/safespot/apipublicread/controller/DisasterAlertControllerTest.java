package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.cache.RegionToGridResolver;
import com.safespot.apipublicread.dto.DisasterAlertItem;
import com.safespot.apipublicread.dto.DisasterLatestDto;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.exception.GlobalExceptionHandler;
import com.safespot.apipublicread.service.DisasterAlertReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DisasterAlertController.class)
@Import(GlobalExceptionHandler.class)
class DisasterAlertControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean DisasterAlertReadService disasterAlertReadService;
    @MockitoBean RegionToGridResolver regionToGridResolver;

    @BeforeEach
    void stubSeoulRegion() {
        lenient().when(regionToGridResolver.isSupported("서울특별시")).thenReturn(true);
        lenient().when(regionToGridResolver.isSupported("서울")).thenReturn(true);
        lenient().when(regionToGridResolver.isSupported("부산광역시")).thenReturn(false);
        lenient().when(regionToGridResolver.isSupported("부산")).thenReturn(false);
    }

    @Test
    void getAlerts_success_emptyList() throws Exception {
        when(disasterAlertReadService.findAlerts(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/disaster-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void getAlerts_withItems() throws Exception {
        DisasterAlertItem item = new DisasterAlertItem(
                55L, "FLOOD", "서울특별시", "주의",
                "한강 수위 상승", "2026-04-14T08:55:00+09:00", null
        );
        when(disasterAlertReadService.findAlerts("서울특별시", "FLOOD")).thenReturn(List.of(item));

        mockMvc.perform(get("/disaster-alerts")
                        .param("region", "서울특별시")
                        .param("disasterType", "FLOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].alertId").value(55));
    }

    @Test
    void getAlerts_nonSeoulRegion_returns400() throws Exception {
        mockMvc.perform(get("/disaster-alerts").param("region", "부산광역시"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_REGION"));
    }

    @Test
    void getAlerts_invalidDisasterType_returns400() throws Exception {
        mockMvc.perform(get("/disaster-alerts").param("disasterType", "TSUNAMI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getLatest_success() throws Exception {
        DisasterLatestDto dto = new DisasterLatestDto(
                55L, "EARTHQUAKE", "서울특별시", "주의",
                "지진 감지", "2026-04-14T08:55:00+09:00", null,
                new DisasterLatestDto.DisasterDetails(4.3, "경기 북부", "IV")
        );
        when(disasterAlertReadService.findLatest("EARTHQUAKE", "서울특별시")).thenReturn(dto);

        mockMvc.perform(get("/disasters/EARTHQUAKE/latest")
                        .param("region", "서울특별시"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alertId").value(55))
                .andExpect(jsonPath("$.data.details.magnitude").value(4.3));
    }

    @Test
    void getLatest_nonSeoulRegion_returns400() throws Exception {
        mockMvc.perform(get("/disasters/EARTHQUAKE/latest").param("region", "부산광역시"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_REGION"));
    }

    @Test
    void getLatest_missingRegion_returns400() throws Exception {
        mockMvc.perform(get("/disasters/EARTHQUAKE/latest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void getLatest_invalidDisasterType_returns400() throws Exception {
        mockMvc.perform(get("/disasters/TSUNAMI/latest").param("region", "서울특별시"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getLatest_notFound_returns404() throws Exception {
        when(disasterAlertReadService.findLatest(any(), any()))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/disasters/FLOOD/latest").param("region", "서울특별시"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
