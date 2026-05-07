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
 */
@Component
public class ForestryLandslideHandler extends AbstractIngestionHandler {

    @Value("${FORESTRY_API_KEY:${FORESTRY_SERVICE_KEY:DUMMY_KEY}}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "FORESTRY_LANDSLIDE";
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
