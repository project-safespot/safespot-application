package com.safespot.externalingestion.handler.groupa2;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 기상청 단기예보 API (KMA_WEATHER)
 * 실행 방식: CronJob (매시 정각) | 일일 한도: 10,000회
 * 정규화 대상: weather_log
 */
@Component
public class KmaWeatherHandler extends AbstractIngestionHandler {

    @Value("${KMA_API_KEY:DUMMY_KEY}")
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
    protected Map<String, String> buildRequestParams() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> params = new HashMap<>();
        params.put("serviceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "1000");
        params.put("dataType", "JSON");
        params.put("base_date", now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        params.put("base_time", "0500");
        params.put("nx", DEFAULT_NX);
        params.put("ny", DEFAULT_NY);
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
