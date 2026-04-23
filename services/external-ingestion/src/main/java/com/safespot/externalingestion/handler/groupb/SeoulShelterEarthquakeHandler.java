package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 서울시 지진옥외대피소 (SEOUL_SHELTER_EARTHQUAKE)
 * 실행 방식: CronJob (매일 02:00) + 초기 배치
 * 정규화 대상: shelter (selective upsert)
 */
@Component
public class SeoulShelterEarthquakeHandler extends AbstractIngestionHandler {

    @Value("${SEOUL_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SEOUL_SHELTER_EARTHQUAKE";
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("KEY", apiKey);
        params.put("Type", "json");
        params.put("pIndex", "1");
        params.put("pSize", "1000");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "TbEqkShelterInfo", "row");
    }
}
