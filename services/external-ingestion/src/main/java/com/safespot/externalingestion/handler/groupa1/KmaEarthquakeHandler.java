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
 * 기상청 지진 정보 API (KMA_EARTHQUAKE) - EqkInfoService/getEqkMsg
 * 수집 주기: 1분
 * 현재 계약: raw payload 수집만 유지하고 disaster_alert에는 적재하지 않는다.
 * auth: query param ServiceKey
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
