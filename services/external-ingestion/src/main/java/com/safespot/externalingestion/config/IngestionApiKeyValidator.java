package com.safespot.externalingestion.config;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * dev/prod 기동 시 enabled source의 API 인증키가 DUMMY_KEY 또는 미설정인 경우 startup fail.
 * ingestion.api-key-validation.fail-on-dummy=true 인 경우에만 활성화.
 * local/test는 application-local.yml에서 false(기본값)로 유지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingestion.api-key-validation.fail-on-dummy", havingValue = "true")
public class IngestionApiKeyValidator implements ApplicationRunner {

    private final List<AbstractIngestionHandler> handlers;

    @Override
    public void run(ApplicationArguments args) {
        for (AbstractIngestionHandler handler : handlers) {
            if (!handler.isEnabled()) {
                continue;
            }
            String key = handler.getProviderApiKey();
            if (key == null) {
                continue; // 파일 기반 source 등 키가 없는 핸들러
            }
            if (key.isBlank() || "DUMMY_KEY".equals(key)) {
                throw new IllegalStateException(
                    "API key for source [" + handler.getSourceCode() + "] is not configured. " +
                    "Set the required environment variable before starting in this profile.");
            }
        }
        log.info("[IngestionApiKeyValidator] API key validation passed for all enabled sources");
    }
}
