package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 산림청 산사태 위험 예측 API (FORESTRY_LANDSLIDE)
 * 폴링 주기: 5분 | 일일 한도: 10,000회 (개발)
 * 정규화 대상: disaster_alert
 *
 * NOTE: 산림청 인증키 승인 대기 중 — isEnabled()=false 상태 유지.
 *       승인 완료 후 FORESTRY_LANDSLIDE_ENABLED=true 환경변수로 활성화.
 */
@Component
public class ForestryLandslideHandler extends AbstractIngestionHandler {

    @Value("${FORESTRY_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Value("${FORESTRY_LANDSLIDE_ENABLED:false}")
    private boolean enabled;

    @Override
    public String getSourceCode() {
        return "FORESTRY_LANDSLIDE";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected int getRateLimitPerDay() {
        return 10000;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("serviceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "50");
        params.put("dataType", "JSON");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
