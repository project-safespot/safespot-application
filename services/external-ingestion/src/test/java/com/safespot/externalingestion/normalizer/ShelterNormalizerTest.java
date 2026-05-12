package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.*;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.repository.ShelterRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShelterNormalizerTest {

    @Mock private ShelterRepository shelterRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());

    private ShelterNormalizer normalizer(String sourceCode) {
        return new ShelterNormalizer(sourceCode, shelterRepo, metrics, objectMapper, cacheEventPublisher);
    }

    // ── LANDSLIDE ──────────────────────────────────────────────────────────────

    @Test
    void landslide_row_without_coords_is_saved() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        // LANDSLIDE source provides no coordinate fields; shelter latitude/longitude are nullable.
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
        verify(shelterRepo).save(argThat(s ->
            "LANDSLIDE".equals(s.getDisasterType()) &&
            s.getLatitude() == null &&
            s.getLongitude() == null &&
            Integer.valueOf(333).equals(s.getCapacity())
        ));
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
    void missing_capacity_is_saved_with_null_capacity() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        // capacity is nullable in the shelter table.
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
        verify(shelterRepo).save(argThat(s ->
            "LANDSLIDE".equals(s.getDisasterType()) &&
            s.getCapacity() == null
        ));
    }

    // ── EARTHQUAKE ─────────────────────────────────────────────────────────────

    @Test
    void earthquake_row_with_official_tletqkp_fields_is_saved() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_EARTHQUAKE").normalize(buildRaw("SEOUL_SHELTER_EARTHQUAKE", """
            {
              "TlEtqkP": {
                "row": [
                  {
                    "FCLT_NO": "188",
                    "ACTC_FCLT_NM": "광양중학교",
                    "DADDR": "서울특별시 광진구 자양로3길 7",
                    "LAT": "37.5300000",
                    "LOT": "127.0830000",
                    "FCAR": "333",
                    "XCRD": "208982.859139",
                    "YCRD": "548967.597592"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(shelterRepo).save(argThat(s ->
            "EARTHQUAKE".equals(s.getDisasterType()) &&
            "광양중학교".equals(s.getName()) &&
            Integer.valueOf(333).equals(s.getCapacity()) &&
            s.getLatitude() != null &&
            s.getLongitude() != null
        ));
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
    void missing_expected_payload_path_fails_contract_mismatch() {
        NormalizationResult result = normalizer("SEOUL_SHELTER_EARTHQUAKE").normalize(buildRaw("SEOUL_SHELTER_EARTHQUAKE", """
            {
              "TbEqKkenvinfo": {
                "row": [
                  {
                    "ACTC_FCLT_NM": "wrong endpoint payload",
                    "DADDR": "서울"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isGreaterThan(0);
        assertThat(result.getErrors()).anyMatch(e -> e.contains("TlEtqkP.row"));
        verify(shelterRepo, never()).save(any());
    }

    // ── FLOOD (좌표 + capacity 유효 → 저장 정상 동작) ──────────────────────────

    @Test
    void flood_row_with_valid_coords_and_capacity_is_saved() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", """
            {
              "TbFloodShelterInfo": {
                "row": [
                  {
                    "SHELTER_NM": "한강대교대피소",
                    "RD_ADDR": "서울 용산구 이촌동 1",
                    "MAN_CNT": "500",
                    "LAT": "37.5172",
                    "LOT": "126.9733"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);

        ArgumentCaptor<Shelter> captor = ArgumentCaptor.forClass(Shelter.class);
        verify(shelterRepo).save(captor.capture());
        Shelter saved = captor.getValue();

        assertThat(saved.getName()).isEqualTo("한강대교대피소");
        assertThat(saved.getAddress()).isEqualTo("서울 용산구 이촌동 1");
        assertThat(saved.getDisasterType()).isEqualTo("FLOOD");
        assertThat(saved.getCapacity()).isEqualTo(500);
        assertThat(saved.getLatitude()).isNotNull();
        assertThat(saved.getLongitude()).isNotNull();
        assertThat(saved.getShelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void flood_upserts_existing_row_without_creating_new() {
        Shelter existing = new Shelter();
        existing.setName("한강대교대피소");
        existing.setAddress("서울 용산구 이촌동 1");
        existing.setDisasterType("FLOOD");

        given(shelterRepo.findByNameAndAddressAndDisasterType("한강대교대피소", "서울 용산구 이촌동 1", "FLOOD"))
            .willReturn(Optional.of(existing));
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        String payload = """
            {
              "TbFloodShelterInfo": {
                "row": [
                  {
                    "SHELTER_NM": "한강대교대피소",
                    "RD_ADDR": "서울 용산구 이촌동 1",
                    "MAN_CNT": "500",
                    "LAT": "37.5172",
                    "LOT": "126.9733"
                  }
                ]
              }
            }
            """;

        normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", payload));
        normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", payload));

        // Both runs must find-and-update the same row, never create a second one
        verify(shelterRepo, times(2)).findByNameAndAddressAndDisasterType(
            "한강대교대피소", "서울 용산구 이촌동 1", "FLOOD");
        verify(shelterRepo, times(2)).save(same(existing));
    }

    // ── Seoul API RESULT.CODE guard ────────────────────────────────────────────

    @Test
    void seoul_api_error_result_code_returns_failure() {
        NormalizationResult result = normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", """
            {
              "RESULT": {
                "CODE": "INFO-100",
                "MESSAGE": "해당하는 데이터가 없습니다."
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(0);
        assertThat(result.getFailed()).isGreaterThan(0);
        verify(shelterRepo, never()).save(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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
