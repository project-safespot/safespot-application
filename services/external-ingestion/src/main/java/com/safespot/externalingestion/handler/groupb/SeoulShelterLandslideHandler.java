package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 산사태 대피소 (SEOUL_SHELTER_LANDSLIDE) — odcloud API
 * 공식 endpoint: api.odcloud.kr/api/15118898/v1/uddi:19815091-0f2c-4d7a-a77f-96cec77038ad
 * auth: query param serviceKey (env: ODCLOUD_API_KEY)
 * 실행 방식: CronJob (매일 02:00) + 초기 배치
 * 정규화 대상: shelter (selective upsert)
 * TODO: verification required — odcloud 응답 필드명 확인 후 ShelterNormalizer 갱신 필요.
 *       현재 SHELTER_NM/RD_ADDR 등 서울 OpenAPI 필드명은 odcloud 실제 응답과 다를 수 있음.
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
        params.put("returnType", "json");
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "data");
    }
}
