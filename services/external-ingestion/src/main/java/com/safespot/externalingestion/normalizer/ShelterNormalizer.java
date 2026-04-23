package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.Shelter;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.repository.ShelterRepository;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 대피소 마스터 정규화 — SEOUL_SHELTER_* 소스 공용 (selective upsert)
 * 허용 컬럼만 갱신: name, shelter_type, disaster_type, address, latitude, longitude, capacity
 * 금지 컬럼: manager, contact, shelter_status, note
 *
 * TODO: shelter 외부 식별자 미정의 — name+address+disasterType 임시 키 사용.
 *       서울 공공데이터 포털 식별자 확정 후 unique key 변경 필요.
 */
@Slf4j
public class ShelterNormalizer implements Normalizer {

    private static final Map<String, String[]> SOURCE_META = Map.of(
        "SEOUL_SHELTER_EARTHQUAKE", new String[]{"TbEqkShelterInfo", "EARTHQUAKE"},
        "SEOUL_SHELTER_LANDSLIDE",  new String[]{"TbLdslShelterInfo", "LANDSLIDE"},
        "SEOUL_SHELTER_FLOOD",      new String[]{"TbFloodShelterInfo", "FLOOD"}
    );

    private final String sourceCode;
    private final ShelterRepository shelterRepo;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    public ShelterNormalizer(String sourceCode, ShelterRepository shelterRepo,
                              IngestionMetrics metrics, ObjectMapper objectMapper) {
        this.sourceCode = sourceCode;
        this.shelterRepo = shelterRepo;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getSourceCode() {
        return sourceCode;
    }

    @Override
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        String[] meta = SOURCE_META.get(sourceCode);
        if (meta == null) {
            return NormalizationResult.failure("unknown shelter source: " + sourceCode);
        }
        String rootKey = meta[0];
        String disasterType = meta[1];

        List<String> errors = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode rows = root.path(rootKey).path("row");
            if (rows.isMissingNode() || rows.isEmpty()) return NormalizationResult.success(0);

            for (JsonNode row : rows) {
                try {
                    String name    = row.path("SHELTER_NM").asText("").trim();
                    String address = row.path("RD_ADDR").asText("").trim();
                    String stype   = row.path("SHELT_TP").asText("").trim();
                    BigDecimal lat = parseDecimal(row.path("LAT").asText("0"));
                    BigDecimal lon = parseDecimal(row.path("LOT").asText("0"));
                    int capacity   = parseIntSafe(row.path("MAN_CNT").asText("0"));

                    if (name.isBlank() || address.isBlank()) {
                        log.debug("[{}] skip row — missing name or address", sourceCode);
                        continue;
                    }

                    Optional<Shelter> existing =
                        shelterRepo.findByNameAndAddressAndDisasterType(name, address, disasterType);

                    Shelter shelter = existing.orElseGet(Shelter::new);
                    shelter.updateFromExternalSource(name, stype, disasterType, address, lat, lon, capacity);
                    shelterRepo.save(shelter);

                    metrics.incrementNormalizationSuccess(sourceCode);
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(sourceCode, "validation_error");
                    log.warn("[{}] shelter upsert failed raw_id={}", sourceCode, raw.getRawId(), e);
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(sourceCode, "parse_error");
            log.error("[{}] parse failed raw_id={}", sourceCode, raw.getRawId(), e);
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private BigDecimal parseDecimal(String val) {
        try { return new BigDecimal(val.trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private int parseIntSafe(String val) {
        try { return Integer.parseInt(val.trim()); } catch (Exception e) { return 0; }
    }
}
