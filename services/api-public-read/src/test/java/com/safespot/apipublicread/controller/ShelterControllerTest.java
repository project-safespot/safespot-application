package com.safespot.apipublicread.controller;

import com.safespot.apipublicread.dto.ShelterDetailDto;
import com.safespot.apipublicread.dto.ShelterMapTileDto;
import com.safespot.apipublicread.dto.ShelterMapTilesResponse;
import com.safespot.apipublicread.dto.ShelterNearbyItem;
import com.safespot.apipublicread.dto.cache.ShelterMapItemCacheDto;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ShelterController.class)
@Import(GlobalExceptionHandler.class)
class ShelterControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ShelterReadService shelterReadService;

    @Test
    void getNearby_success() throws Exception {
        ShelterNearbyItem item = new ShelterNearbyItem(
                101L, "서울시민체육관", "DESIGNATED", "EARTHQUAKE",
                "서울특별시 마포구", 37.5687, 126.9081,
                420, 120, 68, 52, "NORMAL", "OPERATING", "2026-04-14T09:10:00+09:00"
        );
        when(shelterReadService.findNearby(anyDouble(), anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(item));

        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].shelterId").value(101))
                .andExpect(jsonPath("$.data.items[0].shelterName").value("서울시민체육관"));
    }

    @Test
    void getNearby_limitOver50_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getNearby_limitZero_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getNearby_limitNegative_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("limit", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getNearby_limitOne_allows() throws Exception {
        when(shelterReadService.findNearby(anyDouble(), anyDouble(), anyInt(), any(), any(), eq(1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("limit", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void getNearby_limit50_allows() throws Exception {
        when(shelterReadService.findNearby(anyDouble(), anyDouble(), anyInt(), any(), any(), eq(50)))
                .thenReturn(List.of());

        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("limit", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void getNearby_invalidShelterType_returns400() throws Exception {
        mockMvc.perform(get("/shelters/nearby")
                        .param("lat", "37.5663")
                        .param("lng", "126.9779")
                        .param("radius", "1000")
                        .param("shelterType", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_success() throws Exception {
        ShelterMapTilesResponse response = new ShelterMapTilesResponse(
                List.of(new ShelterMapTileDto(
                        13,
                        7285,
                        3172,
                        List.of(new ShelterMapItemCacheDto(1, 101L, "서울시민체육관", "DESIGNATED", "FLOOD", "서울", 120, 37.56, 126.97, "2026-05-15T10:00:00Z")),
                        null
                )),
                null
        );
        when(shelterReadService.findMapTiles(eq(13), eq(List.of("7285:3172")), any(), any())).thenReturn(response);

        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", "7285:3172"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tiles[0].z").value(13))
                .andExpect(jsonPath("$.data.tiles[0].items[0].shelterId").value(101));
    }

    @Test
    void getMapTiles_unsupportedZoom_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "10")
                        .param("tiles", "7285:3172"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_zoom17_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "17")
                        .param("tiles", "7285:3172"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_emptyTiles_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
    }

    @Test
    void getMapTiles_emptyToken_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", "7285:3172,,7285:3173"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_over64Tiles_returns400() throws Exception {
        String tiles = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> "1:" + i)
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();

        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", tiles))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_negativeCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", "-1:3172"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_rangeOverflow_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", "8192:0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getMapTiles_nonNumeric_returns400() throws Exception {
        mockMvc.perform(get("/shelters/map/tiles")
                        .param("z", "13")
                        .param("tiles", "abc:0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getById_success() throws Exception {
        ShelterDetailDto dto = new ShelterDetailDto(
                101L, "서울시민체육관", "민방위대피소", "EARTHQUAKE",
                "서울특별시 마포구", 37.5687, 126.9081,
                120, 68, 52, "NORMAL", "OPERATING",
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
