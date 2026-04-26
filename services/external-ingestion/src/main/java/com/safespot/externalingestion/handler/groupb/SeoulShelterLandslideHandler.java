package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 서울시 산사태 대피소 (SEOUL_SHELTER_LANDSLIDE)
 * 출처: api.odcloud.kr (공공데이터포털) — query-param serviceKey 인증
 * 실행 방식: CronJob (매일 02:00) + 초기 배치
 * 정규화 대상: shelter (selective upsert)
 */
@Component
public class SeoulShelterLandslideHandler extends AbstractIngestionHandler {

    @Value("${ODCLOUD_API_KEY:DUMMY_KEY}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SEOUL_SHELTER_LANDSLIDE";
    }

    @Override
    public String getProviderApiKey() {
        return apiKey;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        Map<String, String> params = new HashMap<>();
        params.put("serviceKey", apiKey);
        params.put("page", "1");
        params.put("perPage", "1000");
        params.put("returnType", "JSON");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "data");
    }
}
