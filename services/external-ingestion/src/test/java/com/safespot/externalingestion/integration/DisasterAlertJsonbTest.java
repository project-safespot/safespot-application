package com.safespot.externalingestion.integration;

import com.safespot.externalingestion.domain.entity.DisasterAlert;
import com.safespot.externalingestion.repository.DisasterAlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL Testcontainer로 JSONB 컬럼 round-trip 검증.
 * Docker가 없으면 SKIPPED (빌드는 통과).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class DisasterAlertJsonbTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    DisasterAlertRepository repo;

    @Test
    void rawCategoryTokens_jsonb_roundTrip() {
        DisasterAlert alert = new DisasterAlert();
        alert.setDisasterType("EARTHQUAKE");
        alert.setRegion("seoul");
        alert.setMessage("테스트 지진");
        alert.setSource("TEST_SRC");
        alert.setIssuedAt(OffsetDateTime.now());
        alert.setRawCategoryTokens("[\"지진\",\"긴급재난\"]");
        alert.setRawLevelTokens("[\"경계\"]");

        DisasterAlert saved = repo.saveAndFlush(alert);

        Optional<DisasterAlert> found = repo.findById(saved.getAlertId());
        assertThat(found).isPresent();
        assertThat(found.get().getRawCategoryTokens()).isEqualTo("[\"지진\",\"긴급재난\"]");
        assertThat(found.get().getRawLevelTokens()).isEqualTo("[\"경계\"]");
    }
}
