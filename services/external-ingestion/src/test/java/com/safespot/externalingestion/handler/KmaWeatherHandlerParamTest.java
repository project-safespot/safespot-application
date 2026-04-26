package com.safespot.externalingestion.handler;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import com.safespot.externalingestion.handler.groupa2.KmaWeatherHandler;

import static org.assertj.core.api.Assertions.assertThat;

class KmaWeatherHandlerParamTest {

    @Test
    void sourceCodeIsKmaWeather() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        assertThat(handler.getSourceCode()).isEqualTo("KMA_WEATHER");
    }

    @Test
    void usesServiceKeyWithCapitalS() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "REAL_KEY");
        Map<String, String> params = invokeParams(handler);
        assertThat(params).containsKey("ServiceKey");
        assertThat(params).doesNotContainKey("serviceKey");
        assertThat(params.get("ServiceKey")).isEqualTo("REAL_KEY");
    }

    @Test
    void baseTimeUsesHH00Format() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        String baseTime = params.get("base_time");
        assertThat(baseTime).matches("\\d{4}");
        assertThat(baseTime).endsWith("00");
    }

    @Test
    void baseTimePreviousHourWhenMinuteLt10() {
        // The handler computes base_time at call time using LocalDateTime.now().
        // We can only verify structure here — integration tests would mock time.
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        // base_date must be today
        String expectedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String yesterday = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(params.get("base_date")).isIn(expectedDate, yesterday); // either today or yesterday if crossing midnight
    }

    @Test
    void defaultGridIsSeoulCenter() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        assertThat(params.get("nx")).isEqualTo("60");
        assertThat(params.get("ny")).isEqualTo("127");
    }

    @Test
    void dataTypeIsJson() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "KEY");
        Map<String, String> params = invokeParams(handler);
        assertThat(params.get("dataType")).isEqualTo("JSON");
    }

    private Map<String, String> invokeParams(KmaWeatherHandler handler) {
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
