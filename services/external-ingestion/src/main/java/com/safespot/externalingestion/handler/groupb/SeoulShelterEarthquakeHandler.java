package com.safespot.externalingestion.handler.groupb;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import com.safespot.externalingestion.util.SeoulOpenApiUrlBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 서울시 지진옥외대피소 (SEOUL_SHELTER_EARTHQUAKE) — TlEtqkP
 * 실행 방식: PollingScheduler (기본 24시간, dev 5분)
 * 정규화 대상: shelter (selective upsert)
 *
 * DB sourceUrl을 우선 사용하고, 없을 때 정합 URL 상수를 fallback으로 사용한다.
 */
@Component
public class SeoulShelterEarthquakeHandler extends AbstractIngestionHandler {

    private static final String URL_TEMPLATE =
        "http://openapi.seoul.go.kr:8088/{KEY}/json/TlEtqkP/1/1000/";

    @Value("${SEOUL_API_KEY:${SEOUL_SERVICE_KEY:DUMMY_KEY}}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SEOUL_SHELTER_EARTHQUAKE";
    }

    @Override
    protected String buildFinalUrl(String sourceUrl) {
        String template = sourceUrl == null || sourceUrl.isBlank() ? URL_TEMPLATE : sourceUrl;
        return SeoulOpenApiUrlBuilder.buildUrl(template, apiKey);
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
