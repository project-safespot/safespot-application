package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 서울시 지진 발생 현황 API (SEOUL_EARTHQUAKE)
 * 폴링 주기: 30초 | 호출 제한 없음
 * 정규화 대상: disaster_alert (source=SEOUL_EARTHQUAKE)
 */
@Component
public class SeoulEarthquakeHandler extends AbstractIngestionHandler {

    @Value("${SEOUL_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SEOUL_EARTHQUAKE";
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("KEY", apiKey);
        params.put("Type", "json");
        params.put("pIndex", "1");
        params.put("pSize", "20");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "ListEqkEq", "row");
    }
}
