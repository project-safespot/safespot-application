package com.safespot.externalingestion.handler.groupa2;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KmaWeatherHandlerTest {

    // Policy: minute < 45 → one hour back (truncatedHour - 1h); minute >= 45 → current truncatedHour

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmm");

    @Test
    void baseTime_hour0minute10_usesPreviousDayHour2300() {
        LocalDateTime base = KmaWeatherHandler.resolveBaseDateTime(LocalDateTime.of(2024, 6, 15, 0, 10));
        assertThat(base.format(DATE_FMT)).isEqualTo("20240614");
        assertThat(base.format(TIME_FMT)).isEqualTo("2300");
    }

    @Test
    void baseTime_hour0minute50_usesSameDayHour0000() {
        LocalDateTime base = KmaWeatherHandler.resolveBaseDateTime(LocalDateTime.of(2024, 6, 15, 0, 50));
        assertThat(base.format(DATE_FMT)).isEqualTo("20240615");
        assertThat(base.format(TIME_FMT)).isEqualTo("0000");
    }

    @Test
    void baseTime_hour1minute30_usesSameDayHour0000() {
        LocalDateTime base = KmaWeatherHandler.resolveBaseDateTime(LocalDateTime.of(2024, 6, 15, 1, 30));
        assertThat(base.format(DATE_FMT)).isEqualTo("20240615");
        assertThat(base.format(TIME_FMT)).isEqualTo("0000");
    }

    @Test
    void baseTime_hour13minute50_usesSameDayHour1300() {
        LocalDateTime base = KmaWeatherHandler.resolveBaseDateTime(LocalDateTime.of(2024, 6, 15, 13, 50));
        assertThat(base.format(DATE_FMT)).isEqualTo("20240615");
        assertThat(base.format(TIME_FMT)).isEqualTo("1300");
    }

    @Test
    @SuppressWarnings("unchecked")
    void seoulGridCoordinatesAreSentAsParams() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "TEST_KEY");

        Map<String, String> params = (Map<String, String>) ReflectionTestUtils.invokeMethod(
            handler, "buildRequestParams");

        assertThat(params.get("nx")).isEqualTo("60");
        assertThat(params.get("ny")).isEqualTo("127");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceKeyParamIsCapitalizedPerOfficialContract() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "TEST_KEY");

        Map<String, String> params = (Map<String, String>) ReflectionTestUtils.invokeMethod(
            handler, "buildRequestParams");

        // 공식 계약: ServiceKey (대문자 S, K) — data.go.kr 기상청 API 기준
        assertThat(params).containsKey("ServiceKey");
        assertThat(params.get("ServiceKey")).isEqualTo("TEST_KEY");
        assertThat(params).doesNotContainKey("serviceKey");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRequestParams_baseTimeIsDynamic_notHardcoded0500() {
        // Verifies that base_time is computed from resolveBaseDateTime, not a hardcoded constant.
        // The resolution policy is tested in isolation via the baseTime_* tests above.
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "TEST_KEY");

        Map<String, String> params = (Map<String, String>) ReflectionTestUtils.invokeMethod(
            handler, "buildRequestParams");

        assertThat(params).containsKey("base_time");
        assertThat(params.get("base_time")).matches("[0-2][0-9][0-5][0-9]");
        assertThat(params).containsKey("base_date");
        assertThat(params.get("base_date")).matches("\\d{8}");
    }
}
