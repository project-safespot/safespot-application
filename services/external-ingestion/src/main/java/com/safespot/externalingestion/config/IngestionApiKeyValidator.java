package com.safespot.externalingestion.config;

import com.safespot.externalingestion.handler.AbstractIngestionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ingestion.api-key-validation.fail-on-dummy", havingValue = "true")
public class IngestionApiKeyValidator implements ApplicationRunner {

    private final List<AbstractIngestionHandler> handlers;

    @Override
    public void run(ApplicationArguments args) {
        for (AbstractIngestionHandler handler : handlers) {
            if (!handler.isEnabled()) continue;
            String key = handler.getProviderApiKey();
            if (key == null) continue;
            if (key.isBlank() || "DUMMY_KEY".equals(key)) {
                throw new IllegalStateException(
                    "API key for source [" + handler.getSourceCode() + "] is not configured. " +
                    "Set the required environment variable before starting in this profile.");
            }
        }
        log.info("[IngestionApiKeyValidator] API key validation passed for all enabled sources");
    }
}
