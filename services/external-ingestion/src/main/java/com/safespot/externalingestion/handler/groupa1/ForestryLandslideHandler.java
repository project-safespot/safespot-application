package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 산림청 산사태 위험 예측 API (FORESTRY_LANDSLIDE)
 * 수집 주기: 5분
 * 현재 계약: raw payload 수집만 유지하고 disaster_alert에는 적재하지 않는다.
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
        params.put("ServiceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "50");
        params.put("_type", "json");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
