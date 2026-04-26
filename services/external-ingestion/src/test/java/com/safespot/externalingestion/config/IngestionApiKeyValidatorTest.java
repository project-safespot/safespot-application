package com.safespot.externalingestion.config;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionApiKeyValidatorTest {

    @Test
    void failsWhenEnabledSourceHasDummyKey() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("SEOUL_EARTHQUAKE", true, "DUMMY_KEY")));

        assertThatThrownBy(() -> validator.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SEOUL_EARTHQUAKE");
    }

    @Test
    void failsWhenEnabledSourceHasBlankKey() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("KMA_WEATHER", true, "")));

        assertThatThrownBy(() -> validator.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KMA_WEATHER");
    }

    @Test
    void passesWhenSourceIsDisabled() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("FORESTRY_LANDSLIDE", false, "DUMMY_KEY")));

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void passesWhenKeyIsNull_fileBased() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("SEOUL_SHELTER_FLOOD", true, null)));

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void passesWhenKeyIsConfigured() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("SEOUL_EARTHQUAKE", true, "REAL_CONFIGURED_KEY")));

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void errorMessageDoesNotContainActualKeyValue() {
        IngestionApiKeyValidator validator = new IngestionApiKeyValidator(
            List.of(stubHandler("SAFETY_DATA_ALERT", true, "DUMMY_KEY")));

        assertThatThrownBy(() -> validator.run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("DUMMY_KEY"); // 키 값 자체는 오류 메시지에 출력하지 않음
    }

    private AbstractIngestionHandler stubHandler(String sourceCode, boolean enabled, String apiKey) {
        return new AbstractIngestionHandler() {
            @Override public String getSourceCode() { return sourceCode; }
            @Override public boolean isEnabled() { return enabled; }
            @Override public String getProviderApiKey() { return apiKey; }
            @Override protected Map<String, String> buildRequestParams() { return Map.of(); }
            @Override protected int countItems(String body) { return 0; }
        };
    }
}
