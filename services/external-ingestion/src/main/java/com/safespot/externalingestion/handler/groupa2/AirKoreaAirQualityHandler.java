package com.safespot.externalingestion.handler.groupa2;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 에어코리아 대기질 API (AIR_KOREA_AIR_QUALITY)
 * 실행 방식: CronJob (매시 정각) | 일일 한도: 500회 (개발)
 * 정규화 대상: air_quality_log
 * NOTE: 개발 계정 500회 한도 — 운영 계정 전환 전 주기 단축 금지
 */
@Component
public class AirKoreaAirQualityHandler extends AbstractIngestionHandler {

    @Value("${AIR_KOREA_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "AIR_KOREA_AIR_QUALITY";
    }

    @Override
    protected int getRateLimitPerDay() {
        return 500;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("serviceKey", apiKey);
        params.put("returnType", "json");
        params.put("numOfRows", "100");
        params.put("pageNo", "1");
        params.put("sidoName", "서울");
        params.put("ver", "1.0");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items");
    }
}
