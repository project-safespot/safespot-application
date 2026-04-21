package com.safespot.externalingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.externalingestion.metrics.IngestionMetrics;
import com.safespot.externalingestion.normalizer.ShelterNormalizer;
import com.safespot.externalingestion.repository.ShelterRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ShelterNormalizer는 소스별로 인스턴스를 별도 생성해야 하므로 여기서 명시적으로 정의
 */
@Configuration
public class NormalizerConfig {

    @Bean
    public ShelterNormalizer seoulShelterEarthquakeNormalizer(ShelterRepository shelterRepo,
                                                               IngestionMetrics metrics,
                                                               ObjectMapper objectMapper) {
        return new ShelterNormalizer("SEOUL_SHELTER_EARTHQUAKE", shelterRepo, metrics, objectMapper);
    }

    @Bean
    public ShelterNormalizer seoulShelterLandslideNormalizer(ShelterRepository shelterRepo,
                                                              IngestionMetrics metrics,
                                                              ObjectMapper objectMapper) {
        return new ShelterNormalizer("SEOUL_SHELTER_LANDSLIDE", shelterRepo, metrics, objectMapper);
    }

    @Bean
    public ShelterNormalizer seoulShelterFloodNormalizer(ShelterRepository shelterRepo,
                                                          IngestionMetrics metrics,
                                                          ObjectMapper objectMapper) {
        return new ShelterNormalizer("SEOUL_SHELTER_FLOOD", shelterRepo, metrics, objectMapper);
    }
}
