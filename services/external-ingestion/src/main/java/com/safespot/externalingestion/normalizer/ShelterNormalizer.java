package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.Shelter;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.ShelterDataCollectedEvent;
import com.safespot.externalingestion.repository.ShelterRepository;
import com.safespot.externalingestion.util.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shelter master normalization for SEOUL_SHELTER_* sources.
 *
 * SOURCE_META:
 * - rowSubPath=null  -> rootKey array directly (odcloud pattern)
 * - rowSubPath="row" -> rootKey.row object (Seoul OpenAPI pattern)
 *
 * TODO: shelter natural key is still provisional. name+address+disasterType is
 * used until a stable external source identifier is introduced.
 */
@Slf4j
public class ShelterNormalizer implements Normalizer {

    private static final List<String> CANONICAL_DISASTER_TYPES = List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE");
    private static final List<String> CANONICAL_SHELTER_TYPES = List.of("DESIGNATED", "TEMPORARY", "WIDE");

    private static final BigDecimal LAT_MIN = new BigDecimal("33");
    private static final BigDecimal LAT_MAX = new BigDecimal("39");
    private static final BigDecimal LON_MIN = new BigDecimal("124");
    private static final BigDecimal LON_MAX = new BigDecimal("132");

    private static final String QUEUE = "cache-refresh";
    private static final String EVENT_TYPE = "ShelterDataCollected";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private record ShelterSourceMeta(String rootKey, String rowSubPath, String disasterType) {}

    private static final Map<String, ShelterSourceMeta> SOURCE_META = Map.of(
        "SEOUL_SHELTER_EARTHQUAKE", new ShelterSourceMeta("TlEtqkP", "row", "EARTHQUAKE"),
        "SEOUL_SHELTER_LANDSLIDE", new ShelterSourceMeta("data", null, "LANDSLIDE"),
        "SEOUL_SHELTER_FLOOD", new ShelterSourceMeta("TbFloodShelterInfo", "row", "FLOOD")
    );

    private final String sourceCode;
    private final ShelterRepository shelterRepo;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final CacheEventPublisher cacheEventPublisher;

    public ShelterNormalizer(
        String sourceCode,
        ShelterRepository shelterRepo,
        IngestionMetrics metrics,
        ObjectMapper objectMapper,
        CacheEventPublisher cacheEventPublisher
    ) {
        this.sourceCode = sourceCode;
        this.shelterRepo = shelterRepo;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.cacheEventPublisher = cacheEventPublisher;
    }

    @Override
    public String getSourceCode() {
        return sourceCode;
    }

    @Override
    @Transactional
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
                log.info("[{}] no rows at path {}", sourceCode, expectedPath);
                return NormalizationResult.success(0);
            }
            log.info("[{}] extracted {} rows from source", sourceCode, rows.size());

            int debugSampled = 0;
            for (JsonNode row : rows) {
                try {
                    String name = extractName(row);
                    String address = extractAddress(row);

                    if (name == null || address == null) {
                        skippedRequired++;
                        log.debug("[{}] skip row - missing name or address", sourceCode);
                        continue;
                    }

                    BigDecimal[] coords = extractCoords(row);
                    BigDecimal lat = coords[0];
                    BigDecimal lon = coords[1];
                    Integer capacity = extractCapacity(row);
                    String disasterType = resolveDisasterType(meta);
                    String shelterType = resolveShelterType(row, meta);
                    String manager = extractManager(row);
                    String contact = extractContact(row);
                    String note = extractNote(row);

                    if (debugSampled < 3) {
                        log.debug(
                            "[{}] sample name={} address={} disasterType={} shelterType={} capacity={} lat={} lon={}",
                            sourceCode, name, address, disasterType, shelterType, capacity, lat, lon
                        );
                        debugSampled++;
                    }

                    Optional<Shelter> existing = shelterRepo.findByNameAndAddressAndDisasterType(
                        name, address, disasterType
                    );
                    Shelter shelter = existing.orElseGet(Shelter::new);
                    shelter.updateFromExternalSource(
                        name,
                        disasterType,
                        shelterType,
                        address,
                        lat,
                        lon,
                        capacity,
                        manager,
                        contact,
                        note
                    );
                    shelterRepo.save(shelter);

                    metrics.incrementNormalizationSuccess(sourceCode);
                    succeeded++;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(sourceCode, "validation_error");
                    log.warn("[{}] shelter upsert failed raw_id={}", sourceCode, raw.getRawId(), e);
                }
            }
            log.info("[{}] done saved={} skipped_required={}", sourceCode, succeeded, skippedRequired);

        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(sourceCode, "parse_error");
            log.error("[{}] parse failed raw_id={}", sourceCode, raw.getRawId(), e);
        }

        if (succeeded > 0) {
            int capturedCount = succeeded;
            String traceId = raw.getExecutionLog().getTraceId();
            String completedAt = OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String disasterType = meta.disasterType();
            AfterCommit.run(() -> publishEvent(traceId, disasterType, capturedCount, completedAt));
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private void publishEvent(String traceId, String disasterType, int savedCount, String completedAt) {
        try {
            ShelterDataCollectedEvent event = new ShelterDataCollectedEvent(traceId, disasterType, savedCount, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(sourceCode, QUEUE, EVENT_TYPE);
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(sourceCode, QUEUE, EVENT_TYPE);
            log.error(
                "[{}] shelter event publish failed - traceId={} disasterType={} savedCount={} completedAt={}",
                sourceCode, traceId, disasterType, savedCount, completedAt, e
            );
        }
    }

    private String extractName(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE" -> text(row, "대피장소내용");
            case "SEOUL_SHELTER_EARTHQUAKE" -> text(row, "ACTC_FCLT_NM");
            default -> text(row, "SHELTER_NM", "ACTC_FCLT_NM", "대피장소내용");
        };
    }

    private String extractAddress(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE" -> text(row, "대피소주소");
            case "SEOUL_SHELTER_EARTHQUAKE" -> text(row, "DADDR");
            default -> text(row, "RD_ADDR", "DADDR", "대피소주소");
        };
    }

    private String resolveDisasterType(ShelterSourceMeta meta) {
        String disasterType = meta.disasterType().trim().toUpperCase(Locale.ROOT);
        if (!CANONICAL_DISASTER_TYPES.contains(disasterType)) {
            throw new IllegalArgumentException("unsupported canonical disasterType: " + disasterType);
        }
        return disasterType;
    }

    private String resolveShelterType(JsonNode row, ShelterSourceMeta meta) {
        String shelterType = switch (sourceCode) {
            case "SEOUL_SHELTER_EARTHQUAKE" -> "WIDE";
            case "SEOUL_SHELTER_LANDSLIDE" -> "TEMPORARY";
            case "SEOUL_SHELTER_FLOOD" -> resolveFloodShelterType(row);
            default -> throw new IllegalArgumentException("unsupported shelter source: " + sourceCode);
        };
        if (!CANONICAL_SHELTER_TYPES.contains(shelterType)) {
            throw new IllegalArgumentException(
                "unsupported canonical shelterType: " + shelterType + ", source=" + sourceCode + ", disasterType=" + meta.disasterType()
            );
        }
        return shelterType;
    }

    private String resolveFloodShelterType(JsonNode row) {
        String rawShelterType = text(
            row,
            "SHELTER_TYPE",
            "shelterType",
            "shelter_type",
            "FCLT_SE_NM",
            "FCLT_SE",
            "SHNT_PSBLTY_SE_NM",
            "SHNT_PSBLTY_SE",
            "대피소유형",
            "시설구분",
            "시설유형",
            "구분"
        );
        String normalized = normalizeShelterTypeToken(rawShelterType);
        if (normalized != null) {
            return normalized;
        }

        // 운영 DB 현황상 FLOOD는 지정대피소/임시대피소만 사용한다.
        // raw row에서 이를 구분할 수 없으면 보수적으로 DESIGNATED를 기본값으로 둔다.
        return "DESIGNATED";
    }

    private String normalizeShelterTypeToken(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (CANONICAL_SHELTER_TYPES.contains(upper)) {
            return upper;
        }
        if (trimmed.contains("임시")) {
            return "TEMPORARY";
        }
        if (trimmed.contains("지정")) {
            return "DESIGNATED";
        }
        if (trimmed.contains("광역")) {
            return "WIDE";
        }
        return null;
    }

    /**
     * Returns [latitude, longitude]. Both null when no valid WGS84 pair is available.
     *
     * EARTHQUAKE: TlEtqkP exposes LAT/LOT as WGS84 latitude/longitude.
     * LANDSLIDE: current odcloud fixture/contract confirmation does not expose
     * a verified WGS84 pair. Keep null/null until raw payload confirms stable
     * coordinate fields. Do not geocode from address here.
     */
    private BigDecimal[] extractCoords(JsonNode row) {
        if ("SEOUL_SHELTER_LANDSLIDE".equals(sourceCode)) {
            return new BigDecimal[]{null, null};
        }

        BigDecimal lat = validLat(decimal(row, "LAT"));
        BigDecimal lon = validLon(decimal(row, "LOT", "LON"));
        if (lat == null || lon == null) {
            return new BigDecimal[]{null, null};
        }
        return new BigDecimal[]{lat, lon};
    }

    private Integer extractCapacity(JsonNode row) {
        return switch (sourceCode) {
            case "SEOUL_SHELTER_LANDSLIDE" -> integer(row, "대피가능인원수");
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
        if (!"SEOUL_SHELTER_LANDSLIDE".equals(sourceCode)) {
            return null;
        }
        String facility = text(row, "대피장소특이사항");
        String remark = text(row, "비고");
        if (facility == null && remark == null) {
            return null;
        }
        if (facility == null) {
            return remark;
        }
        if (remark == null) {
            return facility;
        }
        return facility + " / " + remark;
    }

    private BigDecimal validLat(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return (value.compareTo(LAT_MIN) >= 0 && value.compareTo(LAT_MAX) <= 0) ? value : null;
    }

    private BigDecimal validLon(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return (value.compareTo(LON_MIN) >= 0 && value.compareTo(LON_MAX) <= 0) ? value : null;
    }

    private String text(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                String value = node.asText("").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private Integer integer(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                if (node.isInt() || node.isLong()) {
                    return node.intValue();
                }
                String value = node.asText("").trim().replace(",", "");
                if (!value.isEmpty()) {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        try {
                            return new BigDecimal(value).intValue();
                        } catch (NumberFormatException ignoredAgain) {
                            // keep scanning candidate fields
                        }
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                if (node.isNumber()) {
                    try {
                        return node.decimalValue();
                    } catch (Exception ignored) {
                        // keep scanning candidate fields
                    }
                }
                String value = node.asText("").trim();
                if (!value.isEmpty()) {
                    try {
                        return new BigDecimal(value);
                    } catch (NumberFormatException ignored) {
                        // keep scanning candidate fields
                    }
                }
            }
        }
        return null;
    }
}
