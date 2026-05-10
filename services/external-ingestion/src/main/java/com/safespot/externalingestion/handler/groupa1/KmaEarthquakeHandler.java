package com.safespot.externalingestion.handler.groupa1;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 기상청 지진 정보 API (KMA_EARTHQUAKE) — EqkInfoService/getEqkMsg
 * 폴링 주기: 1분 | 일일 한도: 10,000회 (개발)
 * 정규화 대상: disaster_alert + disaster_alert_detail
 * auth: query param ServiceKey (대소문자 공식 계약 기준)
 */
@Component
public class KmaEarthquakeHandler extends AbstractIngestionHandler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${KMA_API_KEY:${KMA_SERVICE_KEY:DUMMY_KEY}}")
    private String apiKey;

    @Override
    public String getSourceCode() {
        return "KMA_EARTHQUAKE";
    }

    @Override
    protected int getRateLimitPerDay() {
        return 10000;
    }

    @Override
    public String getProviderApiKey() {
        return apiKey;
    }

    @Override
    protected Map<String, String> buildRequestParams() {
        LocalDate today = LocalDate.now(KST);
        Map<String, String> params = new HashMap<>();
        params.put("ServiceKey", apiKey);
        params.put("pageNo", "1");
        params.put("numOfRows", "10");
        params.put("dataType", "JSON");
        params.put("fromTmFc", today.minusDays(2).format(DATE_FMT));
        params.put("toTmFc", today.format(DATE_FMT));
        return params;
    }

    @Override
    protected int countItems(String responseBody) {
        return countItemsInArray(responseBody, "response", "body", "items", "item");
    }
}
