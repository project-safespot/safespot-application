-- test_scenario_record tracks all test data created by scenario-simulator
-- Used exclusively for cleanup and audit. Does not touch production domain tables.
--
-- NOTE: scenario-simulator does not run Flyway (spring.flyway.enabled=false).
-- 실제 AWS dev migration은 k8s-manifest의 db-migration Job에서 별도 V6로 관리한다.
-- This file is kept as a schema reference only and is not executed by the application.

CREATE TABLE IF NOT EXISTS test_scenario_record (
    record_id     BIGSERIAL    PRIMARY KEY,
    scenario_id   VARCHAR(36)  NOT NULL,
    scenario_name VARCHAR(100),
    target_table  VARCHAR(100) NOT NULL,
    target_id     BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scenario_record_scenario_id
    ON test_scenario_record (scenario_id);

CREATE INDEX IF NOT EXISTS idx_scenario_record_target
    ON test_scenario_record (target_table, target_id);
