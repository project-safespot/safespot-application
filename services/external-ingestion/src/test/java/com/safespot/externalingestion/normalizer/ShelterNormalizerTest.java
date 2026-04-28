package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.repository.ShelterRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterNormalizerTest {

    @Mock private ShelterRepository shelterRepo;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());

    private ShelterNormalizer normalizer(String sourceCode) {
        return new ShelterNormalizer(sourceCode, shelterRepo, metrics, objectMapper);
    }

    @Test
    void landslide_korean_payload_saves_one_row() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", """
            {
              "currentCount": 1,
              "data": [
                {
                  "연번": 4,
                  "담당부서명": "도시녹지과",
                  "담당자전화번호": "02-2148-2855",
                  "대피가능인원수": 333,
                  "대피소주소": "종로구 자하문로 136",
                  "대피장소내용": "경기상업고등학교",
                  "대피장소순번": 3,
                  "대피장소응급시설내용": "임시 구호소",
                  "비고": "북악산, 인왕산"
                }
              ]
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);

        ArgumentCaptor<Shelter> captor = ArgumentCaptor.forClass(Shelter.class);
        verify(shelterRepo).save(captor.capture());
        Shelter saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("경기상업고등학교");
        assertThat(saved.getAddress()).isEqualTo("종로구 자하문로 136");
        assertThat(saved.getDisasterType()).isEqualTo("LANDSLIDE");
        assertThat(saved.getCapacity()).isEqualTo(333);
        assertThat(saved.getManager()).isEqualTo("도시녹지과");
        assertThat(saved.getContact()).isEqualTo("02-2148-2855");
        assertThat(saved.getNote()).contains("임시 구호소");
        assertThat(saved.getNote()).contains("북악산");
        // No coordinates in LANDSLIDE source — must be null, not 0,0
        assertThat(saved.getLatitude()).isNull();
        assertThat(saved.getLongitude()).isNull();
        assertThat(saved.getShelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void earthquake_payload_saves_one_row_with_null_coords() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_EARTHQUAKE").normalize(buildRaw("SEOUL_SHELTER_EARTHQUAKE", """
            {
              "TlEtqkP": {
                "row": [
                  {
                    "FCLT_NO": "188",
                    "FCLT_SN": "15",
                    "CTPV_NM": "서울특별시",
                    "SGG_NM": "광진구",
                    "ACTC_FCLT_NM": "광양중학교",
                    "DADDR": "서울특별시 광진구 자양로3길 7",
                    "STDG_CD": "1121510500",
                    "DONG_CD": "1121583000",
                    "LOT": "127.0830000",
                    "XCRD": "208982.859139",
                    "YCRD": "548967.597592"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);

        ArgumentCaptor<Shelter> captor = ArgumentCaptor.forClass(Shelter.class);
        verify(shelterRepo).save(captor.capture());
        Shelter saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("광양중학교");
        assertThat(saved.getAddress()).isEqualTo("서울특별시 광진구 자양로3길 7");
        assertThat(saved.getDisasterType()).isEqualTo("EARTHQUAKE");
        // XCRD/YCRD are Korean projected coordinates — must not be stored as lat/lon
        // LOT-only (no LAT) → both must be null per coordinate policy
        assertThat(saved.getLatitude()).isNull();
        assertThat(saved.getLongitude()).isNull();
        assertThat(saved.getLatitude()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(saved.getLongitude()).isNotEqualTo(BigDecimal.ZERO);
        assertThat(saved.getShelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void missing_name_skips_row_without_saving() {
        NormalizationResult result = normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", """
            {
              "data": [
                {
                  "대피소주소": "서울 종로구 어딘가"
                }
              ]
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(shelterRepo, never()).save(any());
    }

    @Test
    void missing_address_skips_row_without_saving() {
        NormalizationResult result = normalizer("SEOUL_SHELTER_EARTHQUAKE").normalize(buildRaw("SEOUL_SHELTER_EARTHQUAKE", """
            {
              "TlEtqkP": {
                "row": [
                  {
                    "ACTC_FCLT_NM": "이름만있는대피소"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(0);
        verify(shelterRepo, never()).save(any());
    }

    @Test
    void missing_capacity_becomes_null_not_zero() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", """
            {
              "data": [
                {
                  "대피장소내용": "테스트 대피소",
                  "대피소주소": "서울 종로구 청운동 1"
                }
              ]
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);

        ArgumentCaptor<Shelter> captor = ArgumentCaptor.forClass(Shelter.class);
        verify(shelterRepo).save(captor.capture());
        assertThat(captor.getValue().getCapacity()).isNull();
    }

    @Test
    void duplicate_payload_upserts_existing_row_without_creating_new() {
        Shelter existing = new Shelter();
        existing.setName("경기상업고등학교");
        existing.setAddress("종로구 자하문로 136");
        existing.setDisasterType("LANDSLIDE");

        given(shelterRepo.findByNameAndAddressAndDisasterType("경기상업고등학교", "종로구 자하문로 136", "LANDSLIDE"))
            .willReturn(Optional.of(existing));
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        String payload = """
            {
              "data": [
                {
                  "대피장소내용": "경기상업고등학교",
                  "대피소주소": "종로구 자하문로 136",
                  "대피가능인원수": 333
                }
              ]
            }
            """;

        normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", payload));
        normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", payload));

        // Both runs must find-and-update the same row, never create a second one
        verify(shelterRepo, times(2)).findByNameAndAddressAndDisasterType(
            "경기상업고등학교", "종로구 자하문로 136", "LANDSLIDE");
        verify(shelterRepo, times(2)).save(same(existing));
    }

    private ExternalApiRawPayload buildRaw(String sourceCode, String body) {
        ExternalApiExecutionLog execLog = new ExternalApiExecutionLog();
        execLog.setTraceId("trace-" + sourceCode.toLowerCase());
        ExternalApiSource source = new ExternalApiSource();
        source.setSourceCode(sourceCode);
        ExternalApiRawPayload raw = new ExternalApiRawPayload();
        raw.setRawId(1L);
        raw.setResponseBody(body);
        raw.setExecutionLog(execLog);
        raw.setSource(source);
        return raw;
    }
}
