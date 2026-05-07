package com.safespot.externalingestion.integration;

import com.safespot.externalingestion.domain.entity.ExternalApiExecutionLog;
import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;
import com.safespot.externalingestion.domain.entity.ExternalApiSource;
import com.safespot.externalingestion.domain.enums.ExecutionStatus;
import com.safespot.externalingestion.repository.ExternalApiExecutionLogRepository;
import com.safespot.externalingestion.repository.ExternalApiRawPayloadRepository;
import com.safespot.externalingestion.repository.ExternalApiSourceRepository;
import jakarta.persistence.EntityManager;
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
 * PostgreSQL Testcontainer로 external_api_raw_payload JSONB 컬럼 round-trip 검증.
 * Docker 없이는 SKIPPED (빌드 통과).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Transactional
class ExternalApiRawPayloadJsonbTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:sql/raw_payload_jsonb_test_schema.sql");
    }

    @Autowired
    EntityManager em;

    @Autowired
    ExternalApiSourceRepository sourceRepo;

    @Autowired
    ExternalApiExecutionLogRepository execLogRepo;

    @Autowired
    ExternalApiRawPayloadRepository rawPayloadRepo;

    private ExternalApiSource createSource() {
        ExternalApiSource src = new ExternalApiSource();
        src.setSourceCode("TEST_" + System.nanoTime());
        src.setSourceName("Test Source");
        src.setProvider("Test Provider");
        src.setCategory("DISASTER");
        src.setAuthType("API_KEY");
        return sourceRepo.saveAndFlush(src);
    }

    private ExternalApiExecutionLog createExecLog(ExternalApiSource source) {
        ExternalApiExecutionLog log = new ExternalApiExecutionLog();
        log.setSource(source);
        log.setExecutionStatus(ExecutionStatus.RUNNING);
        log.setStartedAt(OffsetDateTime.now());
        return execLogRepo.saveAndFlush(log);
    }

    @Test
    void insert_minimal_externalApiRawPayload_succeeds() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setResponseBody("{\"status\":\"OK\"}");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);
        assertThat(saved.getRawId()).isNotNull();
    }

    @Test
    void responseBody_jsonObject_roundTrip() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setResponseBody("{\"status\":\"OK\",\"items\":[{\"id\":1,\"name\":\"test\"}]}");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);

        Optional<ExternalApiRawPayload> found = rawPayloadRepo.findById(saved.getRawId());
        assertThat(found).isPresent();
        assertThat(found.get().getResponseBody())
            .contains("\"status\"")
            .contains("\"items\"");
    }

    @Test
    void requestParamsJson_jsonbRoundTrip() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setResponseBody("{\"data\":\"ok\"}");
        payload.setRequestParamsJson("{\"page\":1,\"size\":100}");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);

        Optional<ExternalApiRawPayload> found = rawPayloadRepo.findById(saved.getRawId());
        assertThat(found).isPresent();
        assertThat(found.get().getRequestParamsJson())
            .contains("\"page\"")
            .contains("\"size\"");
    }

    @Test
    void responseMetaJson_jsonbRoundTrip() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setResponseBody("{\"data\":\"ok\"}");
        payload.setResponseMetaJson("{\"httpStatus\":200,\"responseTimeMs\":142}");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);

        Optional<ExternalApiRawPayload> found = rawPayloadRepo.findById(saved.getRawId());
        assertThat(found).isPresent();
        assertThat(found.get().getResponseMetaJson())
            .contains("\"httpStatus\"")
            .contains("200");
    }

    @Test
    void responseBody_jsonArray_roundTrip() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setResponseBody("[{\"id\":1},{\"id\":2}]");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);

        Optional<ExternalApiRawPayload> found = rawPayloadRepo.findById(saved.getRawId());
        assertThat(found).isPresent();
        assertThat(found.get().getResponseBody()).contains("\"id\"");
    }

    @Test
    void allJsonbColumns_flush_commit_roundTrip() {
        ExternalApiSource source = createSource();
        ExternalApiExecutionLog execLog = createExecLog(source);

        ExternalApiRawPayload payload = new ExternalApiRawPayload();
        payload.setSource(source);
        payload.setExecutionLog(execLog);
        payload.setRequestParamsJson("{\"params\":[\"a\",\"b\"]}");
        payload.setResponseBody("{\"result\":\"ok\",\"count\":3}");
        payload.setResponseMetaJson("{\"headers\":{\"x-total\":\"3\"}}");
        payload.setRequestUrl("https://api.example.com/data?page=1");
        payload.setPayloadHash("sha256-abc123def456");

        ExternalApiRawPayload saved = rawPayloadRepo.saveAndFlush(payload);

        // Clear first-level cache to force re-read from PostgreSQL
        em.clear();

        Optional<ExternalApiRawPayload> found = rawPayloadRepo.findById(saved.getRawId());
        assertThat(found).isPresent();

        ExternalApiRawPayload result = found.get();
        // Use contains() to tolerate jsonb whitespace normalization (e.g. adds space after colon)
        assertThat(result.getRequestParamsJson()).contains("\"params\"");
        assertThat(result.getResponseBody()).contains("\"result\"").contains("\"count\"");
        assertThat(result.getResponseMetaJson()).contains("\"headers\"");
        assertThat(result.getRequestUrl()).isEqualTo("https://api.example.com/data?page=1");
        assertThat(result.getPayloadHash()).isEqualTo("sha256-abc123def456");
    }
}
