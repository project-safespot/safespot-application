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
 *
 * SOURCE_META: rowSubPath=null → rootKey 배열 직접 (odcloud 패턴)
 *              rowSubPath="row" → rootKey.row 구조 (서울 OpenAPI 패턴)
 *
 * TODO: shelter 외부 식별자 미정의 — name+address+disasterType 임시 키 사용.
 *       서울 공공데이터 포털 식별자 확정 후 unique key 변경 필요.
 * TODO: source_code/external_id 컬럼이 추가되면 source별 원천 ID 기반 upsert로 전환 필요.
 */
@Slf4j
public class ShelterNormalizer implements Normalizer {

    private static final BigDecimal LAT_MIN = new BigDecimal("33");
    private static final BigDecimal LAT_MAX = new BigDecimal("39");
    private static final BigDecimal LON_MIN = new BigDecimal("124");
    private static final BigDecimal LON_MAX = new BigDecimal("132");

    private record ShelterSourceMeta(String rootKey, String rowSubPath, String disasterType) {}

    private static final Map<String, ShelterSourceMeta> SOURCE_META = Map.of(
        "SEOUL_SHELTER_EARTHQUAKE", new ShelterSourceMeta("TlEtqkP",            "row", "EARTHQUAKE"),
        "SEOUL_SHELTER_LANDSLIDE",  new ShelterSourceMeta("data",               null,  "LANDSLIDE"),
        "SEOUL_SHELTER_FLOOD",      new ShelterSourceMeta("TbFloodShelterInfo", "row", "FLOOD")
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
        ShelterSourceMeta meta = SOURCE_META.get(sourceCode);
        if (meta == null) {
            return NormalizationResult.failure("unknown shelter source: " + sourceCode);
        }

        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        int skippedRequired = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());

            // Seoul OpenAPI sources (rowSubPath != null) return a top-level RESULT node on error.
            if (meta.rowSubPath() != null) {
                JsonNode resultCode = root.path("RESULT").path("CODE");
                if (!resultCode.isMissingNode()) {
                    String code = resultCode.asText("");
                    if (!"INFO-000".equals(code)) {
                        String msg = root.path("RESULT").path("MESSAGE").asText("");
                        log.warn("[{}] Seoul API error raw_id={} CODE={} MESSAGE={}", sourceCode, raw.getRawId(), code, msg);
                        return NormalizationResult.failure("Seoul API error CODE=" + code + " " + msg);
                    }
                }
            }

            JsonNode rows = meta.rowSubPath() != null
                ? root.path(meta.rootKey()).path(meta.rowSubPath())
                : root.path(meta.rootKey());

            String expectedPath = meta.rootKey() + (meta.rowSubPath() != null ? "." + meta.rowSubPath() : "");
            if (rows.isMissingNode()) {
                String message = "missing expected shelter payload path: " + expectedPath;
                log.warn("[{}] {} raw_id={}", sourceCode, message, raw.getRawId());
                metrics.incrementNormalizationFailure(sourceCode, "contract_mismatch");
                return NormalizationResult.failure(message);
            }

            if (rows.isEmpty()) {
                log.info("[{}] no rows at path {}{}", sourceCode, meta.rootKey(),
                    meta.rowSubPath() != null ? "." + meta.rowSubPath() : "");
                return NormalizationResult.success(0);
            }
            log.info("[{}] extracted {} rows from source", sourceCode, rows.size());

            int debugSampled = 0;
            for (JsonNode row : rows) {
                try {
                    String name    = extractName(row);
                    String address = extractAddress(row);

                    if (name == null || address == null) {
                        skippedRequired++;
                        log.debug("[{}] skip row — missing name or address", sourceCode);
                        continue;
                    }

                    BigDecimal[] coords   = extractCoords(row);
                    BigDecimal   lat      = coords[0];
                    BigDecimal   lon      = coords[1];
                    Integer      capacity = extractCapacity(row);

                    String manager = extractManager(row);
                    String contact = extractContact(row);
                    String note    = extractNote(row);

                    if (debugSampled < 3) {
                        log.debug("[{}] sample name={} address={} capacity={} lat={} lon={}",
                            sourceCode, name, address, capacity, lat, lon);
                        debugSampled++;
                    }

                    Optional<Shelter> existing = shelterRepo.findByNameAndAddressAndDisasterType(
                        name, address, meta.disasterType());
                    Shelter shelter = existing.orElseGet(Shelter::new);
                    shelter.updateFromExternalSource(name, meta.disasterType(), meta.disasterType(),
                        address, lat, lon, capacity, manager, contact, note);
                    shelterRepo.save(shelter);

                    metrics.incrementNormalizationSuccess(sourceCode);
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(sourceCode, "validation_error");
                    log.warn("[{}] shelter upsert failed raw_id={}", sourceCode, raw.getRawId(), e);
                }
            }
            log.info("[{}] done saved={} skipped_required={}",
                sourceCode, succeeded, skippedRequired);

        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(sourceCode, "parse_error");
            log.error("[{}] parse failed raw_id={}", sourceCode, raw.getRawId(), e);
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private String extractName(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE"  -> text(row, "대피장소내용");
            case "SEOUL_SHELTER_EARTHQUAKE" -> text(row, "ACTC_FCLT_NM");
            default -> text(row, "SHELTER_NM", "ACTC_FCLT_NM", "대피장소내용");
        };
    }

    private String extractAddress(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE"  -> text(row, "대피소주소");
            case "SEOUL_SHELTER_EARTHQUAKE" -> text(row, "DADDR");
            default -> text(row, "RD_ADDR", "DADDR", "대피소주소");
        };
    }

    /**
     * Returns [latitude, longitude]. Both null when no valid WGS84 pair is available.
     *
     * EARTHQUAKE: TlEtqkP exposes LAT/LOT as WGS84 latitude/longitude.
     * LANDSLIDE:  no coordinate fields in this source.
     */
    private BigDecimal[] extractCoords(JsonNode row) {
        if ("SEOUL_SHELTER_LANDSLIDE".equals(sourceCode)) {
            return new BigDecimal[]{null, null};
        }
        // Generic fallback: try standard WGS84 field names
        BigDecimal lat = validLat(decimal(row, "LAT"));
        BigDecimal lon = validLon(decimal(row, "LOT", "LON"));
        if (lat == null || lon == null) return new BigDecimal[]{null, null};
        return new BigDecimal[]{lat, lon};
    }

    private Integer extractCapacity(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE"  -> integer(row, "대피가능인원수");
            case "SEOUL_SHELTER_EARTHQUAKE" -> integer(row, "FCAR", "MAN_CNT");
            default -> integer(row, "MAN_CNT", "대피가능인원수");
        };
    }

    private String extractManager(JsonNode row) {
        return "SEOUL_SHELTER_LANDSLIDE".equals(sourceCode) ? text(row, "담당부서명") : null;
    }

    private String extractContact(JsonNode row) {
        return "SEOUL_SHELTER_LANDSLIDE".equals(sourceCode) ? text(row, "담당자전화번호") : null;
    }

    private String extractNote(JsonNode row) {
        if (!"SEOUL_SHELTER_LANDSLIDE".equals(sourceCode)) return null;
        String facility = text(row, "대피장소응급시설내용");
        String remark   = text(row, "비고");
        if (facility == null && remark == null) return null;
        if (facility == null) return remark;
        if (remark   == null) return facility;
        return facility + " / " + remark;
    }

    private BigDecimal validLat(BigDecimal v) {
        if (v == null) return null;
        return (v.compareTo(LAT_MIN) >= 0 && v.compareTo(LAT_MAX) <= 0) ? v : null;
    }

    private BigDecimal validLon(BigDecimal v) {
        if (v == null) return null;
        return (v.compareTo(LON_MIN) >= 0 && v.compareTo(LON_MAX) <= 0) ? v : null;
    }

    // --- safe extraction helpers ---

    /** Returns trimmed non-blank text from the first matching key, or null. */
    private String text(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                String val = node.asText("").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    /** Parses an integer from the first matching key. Handles numeric JSON, numeric strings,
     *  and comma-formatted numbers. Returns null for blank or unparseable values. */
    private Integer integer(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                if (node.isInt() || node.isLong()) return node.intValue();
                String s = node.asText("").trim().replace(",", "");
                if (!s.isEmpty()) {
                    try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
                    try { return new BigDecimal(s).intValue(); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }

    /** Parses a BigDecimal from the first matching key. Returns null for blank or unparseable values. */
    private BigDecimal decimal(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                if (node.isNumber()) {
                    try { return node.decimalValue(); } catch (Exception ignored) {}
                }
                String s = node.asText("").trim();
                if (!s.isEmpty()) {
                    try { return new BigDecimal(s); } catch (NumberFormatException ignored) {}
                }
            }
        }
        return null;
    }
}
