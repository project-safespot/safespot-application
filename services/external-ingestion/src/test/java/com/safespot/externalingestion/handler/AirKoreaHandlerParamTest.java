package com.safespot.externalingestion.handler;

import com.safespot.externalingestion.handler.groupa2.AirKoreaAirQualityHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AirKoreaHandlerParamTest {

    @Test
    void sourceCodeIsAirKoreaAirQuality() {
        assertThat(new AirKoreaAirQualityHandler().getSourceCode()).isEqualTo("AIR_KOREA_AIR_QUALITY");
    }

    @Test
    void paramsMatchGetMinuDustFrcstDspthContract() {
        AirKoreaAirQualityHandler handler = new AirKoreaAirQualityHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "REAL_KEY");
        Map<String, String> params = invokeParams(handler);

        assertThat(params.get("serviceKey")).isEqualTo("REAL_KEY");
        assertThat(params.get("returnType")).isEqualTo("json");
        assertThat(params.get("InformCode")).isEqualTo("PM10");
        assertThat(params).containsKey("searchDate");
        assertThat(params).containsKey("pageNo");
        assertThat(params).containsKey("numOfRows");
    }

    @Test
    void searchDateIsToday() {
        AirKoreaAirQualityHandler handler = new AirKoreaAirQualityHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        String expectedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertThat(params.get("searchDate")).isEqualTo(expectedDate);
    }

    @Test
    void noOldRealTimeMeasurementParams() {
        AirKoreaAirQualityHandler handler = new AirKoreaAirQualityHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        // Old getCtprvnRltmMesureDnsty params should be gone
        assertThat(params).doesNotContainKey("sidoName");
        assertThat(params).doesNotContainKey("ver");
    }

    private Map<String, String> invokeParams(AirKoreaAirQualityHandler handler) {
        try {
            var method = handler.getClass().getDeclaredMethod("buildRequestParams");
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) method.invoke(handler);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
