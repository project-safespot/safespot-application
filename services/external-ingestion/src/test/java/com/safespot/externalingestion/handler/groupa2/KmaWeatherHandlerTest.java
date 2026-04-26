package com.safespot.externalingestion.handler.groupa2;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KmaWeatherHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void baseTimeIsFixed0500_policyDocument() {
        KmaWeatherHandler handler = new KmaWeatherHandler();
        ReflectionTestUtils.setField(handler, "apiKey", "TEST_KEY");

        Map<String, String> params = (Map<String, String>) ReflectionTestUtils.invokeMethod(
            handler, "buildRequestParams");

        // 현재 정책: 0500 고정. 초단기실황(getUltraSrtNcst) base_time은 매시 HHmm 형식.
        // 운영 전 polling 시각 기준 직전 정시를 동적으로 선택하는 로직 추가 필요.
        // TODO: base_time 동적 선택 구현 후 이 테스트를 갱신한다.
        assertThat(params.get("base_time")).isEqualTo("0500");
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
}
