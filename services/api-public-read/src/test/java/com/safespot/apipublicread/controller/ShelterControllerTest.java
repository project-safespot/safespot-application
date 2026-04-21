package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.exception.ApiException;
import com.safespot.apipublicread.exception.ErrorCode;
import com.safespot.apipublicread.exception.GlobalExceptionHandler;
import com.safespot.apipublicread.service.ShelterReadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShelterController.class)
@Import(GlobalExceptionHandler.class)
class ShelterControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ShelterReadService shelterReadService;

    @Test
    void getNearby_success() throws Exception {
        ShelterNearbyItem item = new ShelterNearbyItem(
                101L, "서울시민체육관", "민방위대피소", "EARTHQUAKE",
                "서울특별시 마포구", 37.5687, 126.9081,
                420, 120, 68, 52, "NORMAL", "운영중", "2026-04-14T09:10:00+09:00"
        );
        when(shelterReadService.findNearby(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(List.of(item));

        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].shelterId").value(101))
                .andExpect(jsonPath("$.data.items[0].shelterName").value("서울시민체육관"))
                .andExpect(jsonPath("$.data.items[0].congestionLevel").value("NORMAL"));
    }

    @Test
    void getNearby_missingLat_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lng", "126.9779")
                        .param("radius", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void getNearby_invalidDisasterType_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("disasterType", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getNearby_radiusOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getById_success() throws Exception {
        ShelterDetailDto dto = new ShelterDetailDto(
                101L, "서울시민체육관", "민방위대피소", "EARTHQUAKE",
                "서울특별시 마포구", 37.5687, 126.9081,
                120, 68, 52, "NORMAL", "운영중",
                "김담당", "02-123-4567", "지하 1층 이용", "2026-04-14T09:10:00+09:00"
        );
        when(shelterReadService.findById(101L)).thenReturn(dto);

        mockMvc.perform(get("/shelters/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shelterId").value(101))
                .andExpect(jsonPath("$.data.manager").value("김담당"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(shelterReadService.findById(999L))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/shelters/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
