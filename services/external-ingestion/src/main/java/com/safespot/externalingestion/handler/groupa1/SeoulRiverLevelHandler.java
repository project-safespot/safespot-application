package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import com.safespot.externalingestion.util.SeoulOpenApiUrlBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 서울시 하천 수위 API (SEOUL_RIVER_LEVEL) - ListRiverStageService
 * 현재 계약: raw payload 수집만 유지하고 disaster_alert에는 적재하지 않는다.
 */
@Component
public class SeoulRiverLevelHandler extends AbstractIngestionHandler {

    @Value("${SEOUL_API_KEY:${SEOUL_SERVICE_KEY:DUMMY_KEY}}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "SEOUL_RIVER_LEVEL";
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
        return countItemsInArray(responseBody, "ListRiverStageService", "row");
    }
}
