package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.domain.entity.Shelter;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ShelterNormalizerTest {

    @Mock private ShelterRepository shelterRepo;
    @Mock private CacheEventPublisher cacheEventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry());

    private ShelterNormalizer normalizer(String sourceCode) {
        return new ShelterNormalizer(sourceCode, shelterRepo, metrics, objectMapper, cacheEventPublisher);
    }

    @Test
    void landslide_row_without_coords_is_saved_as_temporary() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", """
            {
              "currentCount": 1,
              "data": [
                {
                  "담당부서명": "치수안전과",
                  "담당자전화번호": "02-2148-2855",
                  "대피가능인원수": 333,
                  "대피소주소": "종로구 자하문로 136",
                  "대피장소내용": "경기상업고등학교",
                  "대피장소특이사항": "임시 구호소",
                  "비고": "북악산 인접"
                }
              ]
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(shelterRepo).save(argThat(shelter ->
            "LANDSLIDE".equals(shelter.getDisasterType()) &&
            "TEMPORARY".equals(shelter.getShelterType()) &&
            shelter.getLatitude() == null &&
            shelter.getLongitude() == null &&
            Integer.valueOf(333).equals(shelter.getCapacity())
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
    void earthquake_row_is_saved_as_wide() {
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
                    "DADDR": "서울 광진구 자양로 길 7",
                    "LAT": "37.5300000",
                    "LOT": "127.0830000",
                    "FCAR": "333"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(shelterRepo).save(argThat(shelter ->
            "EARTHQUAKE".equals(shelter.getDisasterType()) &&
            "WIDE".equals(shelter.getShelterType()) &&
            "광양중학교".equals(shelter.getName()) &&
            Integer.valueOf(333).equals(shelter.getCapacity()) &&
            shelter.getLatitude() != null &&
            shelter.getLongitude() != null
        ));
    }

    @Test
    void missing_address_skips_earthquake_row_without_saving() {
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
        assertThat(result.getErrors()).anyMatch(error -> error.contains("TlEtqkP.row"));
        verify(shelterRepo, never()).save(any());
    }

    @Test
    void flood_row_without_explicit_type_defaults_to_designated() {
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
        assertThat(saved.getShelterType()).isEqualTo("DESIGNATED");
        assertThat(saved.getCapacity()).isEqualTo(500);
        assertThat(saved.getLatitude()).isNotNull();
        assertThat(saved.getLongitude()).isNotNull();
        assertThat(saved.getShelterStatus()).isEqualTo("OPERATING");
    }

    @Test
    void flood_row_with_temporary_label_is_saved_as_temporary() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        NormalizationResult result = normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", """
            {
              "TbFloodShelterInfo": {
                "row": [
                  {
                    "SHELTER_NM": "임시 수해 대피소",
                    "RD_ADDR": "서울 용산구 이촌동 2",
                    "대피소유형": "임시대피소",
                    "MAN_CNT": "150",
                    "LAT": "37.5173",
                    "LOT": "126.9734"
                  }
                ]
              }
            }
            """));

        assertThat(result.getSucceeded()).isEqualTo(1);
        verify(shelterRepo).save(argThat(shelter ->
            "FLOOD".equals(shelter.getDisasterType()) &&
            "TEMPORARY".equals(shelter.getShelterType())
        ));
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

        verify(shelterRepo, times(2)).findByNameAndAddressAndDisasterType(
            "한강대교대피소", "서울 용산구 이촌동 1", "FLOOD"
        );
        verify(shelterRepo, times(2)).save(same(existing));
        assertThat(existing.getShelterType()).isEqualTo("DESIGNATED");
    }

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

    @Test
    void canonical_shelter_type_never_uses_disaster_type_value() {
        given(shelterRepo.findByNameAndAddressAndDisasterType(anyString(), anyString(), anyString()))
            .willReturn(Optional.empty());
        given(shelterRepo.save(any())).willAnswer(inv -> inv.getArgument(0));

        normalizer("SEOUL_SHELTER_EARTHQUAKE").normalize(buildRaw("SEOUL_SHELTER_EARTHQUAKE", """
            {
              "TlEtqkP": {
                "row": [
                  {
                    "ACTC_FCLT_NM": "지진 대피소",
                    "DADDR": "서울 광진구 예시로 7",
                    "LAT": "37.5300000",
                    "LOT": "127.0830000",
                    "FCAR": "333"
                  }
                ]
              }
            }
            """));
        normalizer("SEOUL_SHELTER_FLOOD").normalize(buildRaw("SEOUL_SHELTER_FLOOD", """
            {
              "TbFloodShelterInfo": {
                "row": [
                  {
                    "SHELTER_NM": "수해 대피소",
                    "RD_ADDR": "서울 용산구 예시로 1",
                    "MAN_CNT": "500",
                    "LAT": "37.5172",
                    "LOT": "126.9733"
                  }
                ]
              }
            }
            """));
        normalizer("SEOUL_SHELTER_LANDSLIDE").normalize(buildRaw("SEOUL_SHELTER_LANDSLIDE", """
            {
              "data": [
                {
                  "대피소주소": "서울 종로구 예시로 136",
                  "대피장소내용": "산사태 대피소"
                }
              ]
            }
            """));

        ArgumentCaptor<Shelter> captor = ArgumentCaptor.forClass(Shelter.class);
        verify(shelterRepo, times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(Shelter::getShelterType)
            .doesNotContain("EARTHQUAKE", "FLOOD", "LANDSLIDE");
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
