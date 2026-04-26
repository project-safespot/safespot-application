package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import com.safespot.externalingestion.util.SeoulOpenApiUrlBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 서울시 지진 발생 현황 API (SEOUL_EARTHQUAKE)
 * 인증: path-based — {KEY} replaced via buildFinalUrl
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
        return countItemsInArray(responseBody, "TbEqkKenvinfo", "row");
    }
}
