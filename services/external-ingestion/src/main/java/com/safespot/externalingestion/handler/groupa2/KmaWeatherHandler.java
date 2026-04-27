package com.safespot.externalingestion.handler.groupa2;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 기상청 초단기실황 API (KMA_WEATHER) — VilageFcstInfoService_2.0/getUltraSrtNcst
 * 실행 방식: CronJob (매시 정각) | 일일 한도: 10,000회
 * 정규화 대상: weather_log
 * auth: query param ServiceKey (대소문자 공식 계약 기준)
 */
@Component
public class KmaWeatherHandler extends AbstractIngestionHandler {

    @Value("${KMA_API_KEY:${KMA_SERVICE_KEY:DUMMY_KEY}}")
    private String apiKey;

    // 서울 중심 격자 좌표 (nx=60, ny=127)
    private static final String DEFAULT_NX = "60";
    private static final String DEFAULT_NY = "127";

    @Override
    public String getSourceCode() {
        return "KMA_WEATHER";
    }

    @Override
    protected int getRateLimitPerDay() {
        return 10000;
    }

    @Override
    public String getProviderApiKey() {
        return apiKey;
    }

    /**
     * 초단기실황 base_date/base_time 결정 정책:
     * - minute < 45 → 1시간 전 HH:00 (데이터 미발표 구간 회피)
     * - minute >= 45 → 현재 HH:00
     * - 00:00~00:44 → 전날 23:00 자동 처리 (minusHours(1))
     */
    static LocalDateTime resolveBaseDateTime(LocalDateTime now) {
        LocalDateTime truncated = now.truncatedTo(ChronoUnit.HOURS);
        return now.getMinute() < 45 ? truncated.minusHours(1) : truncated;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        LocalDateTime base = resolveBaseDateTime(LocalDateTime.now());
        Map<String, String> params = new HashMap<>();
        params.put("ServiceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "1000");
        params.put("dataType", "JSON");
        params.put("base_date", base.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        params.put("base_time", base.format(DateTimeFormatter.ofPattern("HHmm")));
        params.put("nx", DEFAULT_NX);
        params.put("ny", DEFAULT_NY);
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
