package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import com.safespot.externalingestion.util.SeoulOpenApiUrlBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 서울시 지진옥외대피소 (SEOUL_SHELTER_EARTHQUAKE) — TlEtqkP
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
    protected String buildFinalUrl(String sourceUrl) {
        return SeoulOpenApiUrlBuilder.buildUrl(sourceUrl, apiKey);
    }

    @Override
    public String getProviderApiKey() {
        return apiKey;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        return Map.of();
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "TlEtqkP", "row");
    }
}
