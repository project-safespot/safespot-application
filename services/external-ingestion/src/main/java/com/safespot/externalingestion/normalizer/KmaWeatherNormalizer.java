package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.WeatherLog;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.EnvironmentDataCollectedEvent;
import com.safespot.externalingestion.repository.WeatherLogRepository;
import com.safespot.externalingestion.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 기상청 초단기실황 정규화 (KMA_WEATHER → weather_log) — getUltraSrtNcst
 * 응답의 category별 값을 (nx, ny, base_date, base_time) 단위로 병합 후 적재.
 * 초단기실황은 관측값(obsrValue)이며 예보 시각(fcstDate/fcstTime)이 없고 base_time이 관측 시각이다.
 *
 * 예상 응답:
 * {"response": {"body": {"items": {"item": [
 *   {"baseDate":"20260421","baseTime":"1000","category":"T1H",
 *    "obsrValue":"15.0","nx":60,"ny":127}
 * ]}}}}
 *
 * 카테고리 매핑: T1H→tmp, RN1→pcp, PTY→pty, REH→reh, WSD→wsd (SKY/POP 없음)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KmaWeatherNormalizer implements Normalizer {

    private static final String QUEUE = "environment-collection";
    private static final String REGION = "seoul";
    private static final String COLLECTION_TYPE = "WEATHER";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter WINDOW_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");

    private final WeatherLogRepository weatherLogRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    @Override
    public String getSourceCode() {
        return "KMA_WEATHER";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        boolean anyStored = false;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isEmpty()) return NormalizationResult.success(0);

            Map<String, Map<String, String>> grouped = groupByForecastKey(items);

            for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
                try {
                    String[] keyParts = entry.getKey().split("\\|");
                    int nx = Integer.parseInt(keyParts[0]);
                    int ny = Integer.parseInt(keyParts[1]);
                    LocalDate baseDate = LocalDate.parse(keyParts[2], DATE_FMT);
                    String baseTime = keyParts[3];
                    // 초단기실황: 관측 시각 = baseDate + baseTime
                    OffsetDateTime forecastDt = LocalDateTime.parse(keyParts[2] + keyParts[3], DATETIME_FMT)
                        .atZone(KST).toOffsetDateTime();

                    if (weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(
                        nx, ny, baseDate, baseTime, forecastDt)) {
                        continue;
                    }

                    WeatherLog wl = buildWeatherLog(nx, ny, baseDate, baseTime, forecastDt, entry.getValue());
                    weatherLogRepo.save(wl);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    succeeded++;
                    anyStored = true;
                } catch (Exception e) {
                    errors.add(e.getMessage());
                    metrics.incrementNormalizationFailure(getSourceCode(), "validation_error");
                }
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
            metrics.incrementNormalizationFailure(getSourceCode(), "parse_error");
            log.error("[KMA_WEATHER] parse failed raw_id={}", raw.getRawId(), e);
        }

        if (anyStored) {
            String traceId = raw.getExecutionLog().getTraceId();
            OffsetDateTime now = OffsetDateTime.now(KST);
            String completedAt = now.format(ISO_FMT);
            String timeWindow = now.format(WINDOW_FMT);
            AfterCommit.run(() -> publishEvent(traceId, timeWindow, completedAt));
        }

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private Map<String, Map<String, String>> groupByForecastKey(JsonNode items) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (JsonNode item : items) {
            // 초단기실황: fcstDate/fcstTime 없음 — base_date+base_time이 관측 시각
            String key = item.path("nx").asText() + "|" +
                item.path("ny").asText() + "|" +
                item.path("baseDate").asText() + "|" +
                item.path("baseTime").asText();
            result.computeIfAbsent(key, k -> new HashMap<>())
                .put(item.path("category").asText(), item.path("obsrValue").asText());
        }
        return result;
    }

    private WeatherLog buildWeatherLog(int nx, int ny, LocalDate baseDate, String baseTime,
                                        OffsetDateTime forecastDt, Map<String, String> cats) {
        WeatherLog wl = new WeatherLog();
        wl.setNx(nx);
        wl.setNy(ny);
        wl.setBaseDate(baseDate);
        wl.setBaseTime(baseTime);
        wl.setForecastDt(forecastDt);
        // 초단기실황 카테고리: T1H(기온), RN1(1시간 강수), PTY(강수형태), REH(습도), WSD(풍속)
        // SKY(하늘상태), POP(강수확률)은 초단기실황에 없음 — null 저장
        if (cats.containsKey("T1H")) wl.setTmp(new BigDecimal(cats.get("T1H")));
        wl.setPty(mapPty(cats.get("PTY")));
        wl.setPcp(cats.get("RN1"));
        if (cats.containsKey("WSD")) wl.setWsd(new BigDecimal(cats.get("WSD")));
        if (cats.containsKey("REH")) wl.setReh(parseIntSafe(cats.get("REH")));
        return wl;
    }

    private void publishEvent(String traceId, String timeWindow, String completedAt) {
        try {
            EnvironmentDataCollectedEvent event = new EnvironmentDataCollectedEvent(
                traceId, COLLECTION_TYPE, REGION, timeWindow, completedAt);
            cacheEventPublisher.publish(event, QUEUE);
            metrics.incrementSqsPublish(getSourceCode(), QUEUE, "EnvironmentDataCollected");
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode(), QUEUE, "EnvironmentDataCollected");
            log.error("[KMA_WEATHER] event publish failed — traceId={} collectionType={} region={} timeWindow={} completedAt={}",
                traceId, COLLECTION_TYPE, REGION, timeWindow, completedAt, e);
        }
    }

    private String mapPty(String code) {
        if (code == null) return null;
        return switch (code) {
            case "0" -> "없음";
            case "1" -> "비";
            case "2" -> "비/눈";
            case "3" -> "눈";
            case "4" -> "소나기";
            default -> code;
        };
    }

    private Integer parseIntSafe(String val) {
        try {
            return val != null ? Integer.parseInt(val) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
