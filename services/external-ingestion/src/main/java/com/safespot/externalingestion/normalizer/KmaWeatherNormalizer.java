package com.safespot.externalingestion.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.WeatherLog;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.publisher.CacheEventPublisher;
import com.safespot.externalingestion.publisher.event.WeatherCacheRefreshEvent;
import com.safespot.externalingestion.repository.WeatherLogRepository;
import com.safespot.externalingestion.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 기상청 단기예보 정규화 (KMA_WEATHER → weather_log)
 * 응답의 category별 값을 (nx, ny, base_date, base_time, forecast_dt) 단위로 병합 후 적재
 *
 * 예상 응답:
 * {"response": {"body": {"items": {"item": [
 *   {"baseDate":"20260421","baseTime":"0500","category":"TMP",
 *    "fcstDate":"20260421","fcstTime":"0600","fcstValue":"15","nx":60,"ny":127}
 * ]}}}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KmaWeatherNormalizer implements Normalizer {

    private final WeatherLogRepository weatherLogRepo;
    private final CacheEventPublisher cacheEventPublisher;
    private final IngestionMetrics metrics;
    private final ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    @Value("${ingestion.sqs.environment-cache-queue-url:}")
    private String envQueueUrl;

    @Override
    public String getSourceCode() {
        return "KMA_WEATHER";
    }

    @Override
    @Transactional
    public NormalizationResult normalize(ExternalApiRawPayload raw) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;

        try {
            JsonNode root = objectMapper.readTree(raw.getResponseBody());
            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isEmpty()) return NormalizationResult.success(0);

            // (nx, ny, baseDate, baseTime, fcstDate+fcstTime) 기준으로 카테고리 값 병합
            Map<String, Map<String, String>> grouped = groupByForecastKey(items);
            Set<String> publishedKeys = new HashSet<>();

            for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
                try {
                    String[] keyParts = entry.getKey().split("\\|");
                    int nx = Integer.parseInt(keyParts[0]);
                    int ny = Integer.parseInt(keyParts[1]);
                    LocalDate baseDate = LocalDate.parse(keyParts[2], DATE_FMT);
                    String baseTime = keyParts[3];
                    OffsetDateTime forecastDt = LocalDateTime.parse(keyParts[4], DATETIME_FMT)
                        .atZone(KST).toOffsetDateTime();

                    if (weatherLogRepo.existsByNxAndNyAndBaseDateAndBaseTimeAndForecastDt(
                        nx, ny, baseDate, baseTime, forecastDt)) {
                        continue;
                    }

                    WeatherLog log = buildWeatherLog(nx, ny, baseDate, baseTime, forecastDt, entry.getValue());
                    weatherLogRepo.save(log);
                    metrics.incrementNormalizationSuccess(getSourceCode());
                    succeeded++;

                    String publishKey = nx + ":" + ny;
                    if (!publishedKeys.contains(publishKey)) {
                        int capturedNx = nx, capturedNy = ny;
                        String traceId = raw.getExecutionLog().getTraceId();
                        AfterCommit.run(() -> publishCacheEvent(capturedNx, capturedNy, traceId));
                        publishedKeys.add(publishKey);
                    }
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

        return NormalizationResult.of(succeeded, errors.size(), errors);
    }

    private Map<String, Map<String, String>> groupByForecastKey(JsonNode items) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String key = item.path("nx").asText() + "|" +
                item.path("ny").asText() + "|" +
                item.path("baseDate").asText() + "|" +
                item.path("baseTime").asText() + "|" +
                item.path("fcstDate").asText() + item.path("fcstTime").asText();
            result.computeIfAbsent(key, k -> new HashMap<>())
                .put(item.path("category").asText(), item.path("fcstValue").asText());
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
        if (cats.containsKey("TMP")) wl.setTmp(new BigDecimal(cats.get("TMP")));
        wl.setSky(mapSky(cats.get("SKY")));
        wl.setPty(mapPty(cats.get("PTY")));
        if (cats.containsKey("POP")) wl.setPop(parseIntSafe(cats.get("POP")));
        wl.setPcp(cats.get("PCP"));
        if (cats.containsKey("WSD")) wl.setWsd(new BigDecimal(cats.get("WSD")));
        if (cats.containsKey("REH")) wl.setReh(parseIntSafe(cats.get("REH")));
        return wl;
    }

    private String mapSky(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "맑음";
            case "3" -> "구름조금";
            case "4" -> "흐림";
            default -> code;
        };
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

    private void publishCacheEvent(int nx, int ny, String traceId) {
        try {
            cacheEventPublisher.publish(new WeatherCacheRefreshEvent(traceId, nx, ny), envQueueUrl);
            metrics.incrementSqsPublish(getSourceCode());
        } catch (Exception e) {
            metrics.incrementSqsPublishFailure(getSourceCode());
            log.error("[KMA_WEATHER] cache event publish failed nx={} ny={}", nx, ny, e);
        }
    }
}
