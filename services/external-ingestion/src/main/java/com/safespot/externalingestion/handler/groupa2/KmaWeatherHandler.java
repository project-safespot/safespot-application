package com.safespot.externalingestion.handler.groupa2;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    public String getProviderApiKey() {
        return apiKey;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> params = new HashMap<>();
        params.put("ServiceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "1000");
        params.put("dataType", "JSON");
        params.put("base_date", now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        // TODO: 초단기실황 base_time은 매시 HHmm 형식. 현재 0500 고정으로
        //  polling 시각 기준 최신 실황 회차를 조회하지 못할 수 있음.
        //  운영 전 polling 시각 기준으로 직전 정시를 동적 선택하는 로직 추가 필요.
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
