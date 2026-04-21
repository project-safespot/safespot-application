package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 행정안전부 재난문자 API (SAFETY_DATA_ALERT)
 * 폴링 주기: 2분 | 일일 한도: 1,000회
 * 정규화 대상: disaster_alert
 */
@Component
public class SafetyDataAlertHandler extends AbstractIngestionHandler {

    @Value("${SAFETY_DATA_ALERT_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SAFETY_DATA_ALERT";
    }

    @Override
    protected int getRateLimitPerDay() {
        return 1000;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("serviceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "50");
        params.put("type", "json");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
