package com.safespot.prescalingcontroller.repository;

import com.safespot.prescalingcontroller.config.PreScalingProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test using PostgreSQL Testcontainers.
 *
 * @Testcontainers annotation is intentionally OMITTED to avoid the WSL2
 * DockerDesktopClientProviderStrategy NPE bug in Testcontainers 1.20.4.
 *
 * Instead, the container is started in the static initializer block, which:
 * - Starts the container before Spring context and @DynamicPropertySource run
 * - Throws an exception (test FAILS, not SKIPS) if Docker is unavailable
 * - Prevents the false-skip behavior caused by @Testcontainers(disabledWithoutDocker=true)
 *
 * CI (ubuntu-latest) has Docker configured correctly; tests will PASS there.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DisasterAlertPollingRepository.class, PreScalingProperties.class})
class DisasterAlertPollingRepositoryTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DisasterAlertPollingRepository repository;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS disaster_alert (
                    alert_id      BIGSERIAL PRIMARY KEY,
                    disaster_type VARCHAR(20) NOT NULL
                        CHECK (disaster_type IN ('EARTHQUAKE','LANDSLIDE','FLOOD')),
                    level_rank    SMALLINT,
                    issued_at     TIMESTAMPTZ NOT NULL,
                    expired_at    TIMESTAMPTZ,
                    region        VARCHAR(100) NOT NULL DEFAULT 'Seoul',
                    level         VARCHAR(10),
                    message       TEXT NOT NULL DEFAULT '',
                    source        VARCHAR(50) NOT NULL DEFAULT '',
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
        jdbc.execute("DELETE FROM disaster_alert");
    }

    // ── qualifying alert ─────────────────────────────────────────────────────

    @Test
    void activeEarthquakeWarning_returnsActive() {
        insertAlert("EARTHQUAKE", 3, -5, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.ACTIVE);
    }

    @Test
    void activeCritical_returnsActive() {
        insertAlert("FLOOD", 4, -3, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.ACTIVE);
    }

    // ── expired_at IS NULL condition ─────────────────────────────────────────

    @Test
    void expiredAlert_returnsInactive() {
        insertAlert("EARTHQUAKE", 3, -5, -1);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    // ── lookbackMinutes condition (PostgreSQL INTERVAL syntax) ────────────────

    @Test
    void alertBeyondLookbackWindow_returnsInactive() {
        // issued 11 minutes ago — beyond lookbackMinutes=10
        insertAlert("EARTHQUAKE", 3, -11, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    @Test
    void alertWithinLookbackWindow_returnsActive() {
        // issued 9 minutes ago — inside lookbackMinutes=10
        insertAlert("LANDSLIDE", 3, -9, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.ACTIVE);
    }

    // ── level_rank condition ─────────────────────────────────────────────────

    @Test
    void belowMinLevelRank_returnsInactive() {
        // level_rank=2 (CAUTION) below threshold 3
        insertAlert("EARTHQUAKE", 2, -5, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    @Test
    void nullLevelRank_returnsInactive() {
        // NULL level_rank must NOT trigger pre-scaling (unresolved canonical)
        insertAlert("EARTHQUAKE", null, -5, null);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    // ── disaster_type condition ──────────────────────────────────────────────

    @Test
    void allConfiguredDisasterTypes_returnActive() {
        for (String type : List.of("EARTHQUAKE", "FLOOD", "LANDSLIDE")) {
            jdbc.execute("DELETE FROM disaster_alert");
            insertAlert(type, 3, -5, null);
            assertThat(repository.queryCondition())
                    .as("disaster_type=%s should be ACTIVE", type)
                    .isEqualTo(DisasterConditionResult.ACTIVE);
        }
    }

    // ── no active disaster ───────────────────────────────────────────────────

    @Test
    void noRows_returnsInactive() {
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    @Test
    void multipleExpiredAlerts_returnsInactive() {
        insertAlert("EARTHQUAKE", 3, -5, -1);
        insertAlert("FLOOD", 4, -8, -2);
        assertThat(repository.queryCondition()).isEqualTo(DisasterConditionResult.INACTIVE);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /**
     * @param issuedMinAgo  negative = N minutes before NOW (e.g. -5 = 5 min ago)
     * @param expiredMinAgo null = still active; negative = N minutes before NOW
     */
    private void insertAlert(String disasterType, Integer levelRank, int issuedMinAgo, Integer expiredMinAgo) {
        if (expiredMinAgo == null) {
            jdbc.update("""
                    INSERT INTO disaster_alert (disaster_type, level_rank, issued_at, expired_at)
                    VALUES (?, ?, NOW() + (?::int * INTERVAL '1 minute'), NULL)
                    """, disasterType, levelRank, issuedMinAgo);
        } else {
            jdbc.update("""
                    INSERT INTO disaster_alert (disaster_type, level_rank, issued_at, expired_at)
                    VALUES (?, ?, NOW() + (?::int * INTERVAL '1 minute'), NOW() + (?::int * INTERVAL '1 minute'))
                    """, disasterType, levelRank, issuedMinAgo, expiredMinAgo);
        }
    }
}
