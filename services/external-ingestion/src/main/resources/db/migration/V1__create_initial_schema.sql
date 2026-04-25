-- external-ingestion initial schema for PostgreSQL
-- raw_category_tokens / raw_level_tokens are JSONB per contract (JSON string arrays)

CREATE TABLE external_api_source (
    source_id   BIGSERIAL    PRIMARY KEY,
    source_code VARCHAR(50)  NOT NULL UNIQUE,
    source_name VARCHAR(100) NOT NULL,
    provider    VARCHAR(100) NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    auth_type   VARCHAR(30)  NOT NULL,
    base_url    TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE external_api_schedule (
    schedule_id         BIGSERIAL    PRIMARY KEY,
    source_id           BIGINT       NOT NULL REFERENCES external_api_source(source_id),
    schedule_name       VARCHAR(100) NOT NULL,
    cron_expr           VARCHAR(100),
    timezone            VARCHAR(50)  NOT NULL DEFAULT 'Asia/Seoul',
    request_params_json TEXT,
    is_enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    next_scheduled_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ext_schedule_next_run ON external_api_schedule (next_scheduled_at, is_enabled);
CREATE INDEX idx_ext_schedule_source   ON external_api_schedule (source_id);

CREATE TABLE external_api_execution_log (
    execution_id       BIGSERIAL   PRIMARY KEY,
    schedule_id        BIGINT      REFERENCES external_api_schedule(schedule_id),
    source_id          BIGINT      NOT NULL REFERENCES external_api_source(source_id),
    execution_status   VARCHAR(20) NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    ended_at           TIMESTAMPTZ,
    http_status        INT,
    retry_count        INT         NOT NULL DEFAULT 0,
    records_fetched    INT         NOT NULL DEFAULT 0,
    records_normalized INT         NOT NULL DEFAULT 0,
    records_failed     INT         NOT NULL DEFAULT 0,
    error_code         VARCHAR(100),
    error_message      TEXT,
    trace_id           VARCHAR(100),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ext_exec_source_started ON external_api_execution_log (source_id, started_at DESC);
CREATE INDEX idx_ext_exec_status_started ON external_api_execution_log (execution_status, started_at DESC);

CREATE TABLE external_api_raw_payload (
    raw_id               BIGSERIAL    PRIMARY KEY,
    execution_id         BIGINT       NOT NULL REFERENCES external_api_execution_log(execution_id),
    source_id            BIGINT       NOT NULL REFERENCES external_api_source(source_id),
    request_url          TEXT,
    request_params_json  TEXT,
    response_body        TEXT         NOT NULL,
    response_meta_json   TEXT,
    payload_hash         VARCHAR(128),
    collected_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    retention_expires_at TIMESTAMPTZ
);

CREATE INDEX idx_ext_raw_source_collected ON external_api_raw_payload (source_id, collected_at DESC);
CREATE INDEX idx_ext_raw_payload_hash     ON external_api_raw_payload (payload_hash);
CREATE INDEX idx_ext_raw_retention        ON external_api_raw_payload (retention_expires_at);

CREATE TABLE external_api_normalization_error (
    error_id      BIGSERIAL    PRIMARY KEY,
    execution_id  BIGINT       NOT NULL REFERENCES external_api_execution_log(execution_id),
    raw_id        BIGINT       REFERENCES external_api_raw_payload(raw_id),
    source_id     BIGINT       NOT NULL REFERENCES external_api_source(source_id),
    target_table  VARCHAR(100) NOT NULL,
    failed_field  VARCHAR(100),
    raw_fragment  TEXT,
    error_reason  TEXT         NOT NULL,
    resolved      BOOLEAN      NOT NULL DEFAULT FALSE,
    resolved_note TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at   TIMESTAMPTZ
);

CREATE INDEX idx_ext_norm_err_source_created ON external_api_normalization_error (source_id, created_at DESC);
CREATE INDEX idx_ext_norm_err_resolved        ON external_api_normalization_error (resolved, created_at DESC);

CREATE TABLE disaster_alert (
    alert_id             BIGSERIAL    PRIMARY KEY,
    raw_type             VARCHAR(100),
    disaster_type        VARCHAR(20)  NOT NULL,
    -- JSONB: JSON arrays stored as jsonb for indexing and validation
    raw_category_tokens  JSONB,
    message_category     VARCHAR(20),
    raw_level            VARCHAR(100),
    -- JSONB: JSON arrays stored as jsonb for indexing and validation
    raw_level_tokens     JSONB,
    level                VARCHAR(10),
    level_rank           SMALLINT,
    region               VARCHAR(100) NOT NULL,
    source_region        VARCHAR(100),
    message              TEXT         NOT NULL,
    source               VARCHAR(50)  NOT NULL,
    issued_at            TIMESTAMPTZ  NOT NULL,
    is_in_scope          BOOLEAN,
    normalization_reason TEXT,
    expired_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alert_source_issued_at UNIQUE (source, issued_at)
);

CREATE INDEX idx_alert_region_type  ON disaster_alert (region, disaster_type);
CREATE INDEX idx_alert_issued_at    ON disaster_alert (issued_at DESC);
CREATE INDEX idx_alert_source_issued ON disaster_alert (source, issued_at);

CREATE TABLE disaster_alert_detail (
    detail_id   BIGSERIAL    PRIMARY KEY,
    alert_id    BIGINT       NOT NULL UNIQUE REFERENCES disaster_alert(alert_id),
    detail_type VARCHAR(30)  NOT NULL,
    magnitude   NUMERIC(4,1),
    epicenter   VARCHAR(255),
    intensity   VARCHAR(20),
    detail_json TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_detail_type ON disaster_alert_detail (detail_type);

CREATE TABLE weather_log (
    log_id       BIGSERIAL   PRIMARY KEY,
    nx           INT         NOT NULL,
    ny           INT         NOT NULL,
    base_date    DATE        NOT NULL,
    base_time    VARCHAR(4)  NOT NULL,
    forecast_dt  TIMESTAMPTZ NOT NULL,
    tmp          NUMERIC(4,1),
    sky          VARCHAR(10),
    pty          VARCHAR(10),
    pop          INT,
    pcp          VARCHAR(20),
    wsd          NUMERIC(4,1),
    reh          INT,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_weather_grid_base_forecast UNIQUE (nx, ny, base_date, base_time, forecast_dt)
);

CREATE INDEX idx_weather_grid_dt ON weather_log (nx, ny, forecast_dt DESC);

CREATE TABLE air_quality_log (
    log_id       BIGSERIAL   PRIMARY KEY,
    station_name VARCHAR(50) NOT NULL,
    measured_at  TIMESTAMPTZ NOT NULL,
    pm10         INT,
    pm10_grade   VARCHAR(10),
    pm25         INT,
    pm25_grade   VARCHAR(10),
    o3           NUMERIC(4,3),
    o3_grade     VARCHAR(10),
    khai_value   INT,
    khai_grade   VARCHAR(10),
    collected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_air_station_measured_at UNIQUE (station_name, measured_at)
);

CREATE INDEX idx_air_station_measured ON air_quality_log (station_name, measured_at DESC);

CREATE TABLE shelter (
    shelter_id     BIGSERIAL      PRIMARY KEY,
    name           VARCHAR(100)   NOT NULL,
    shelter_type   VARCHAR(50)    NOT NULL,
    disaster_type  VARCHAR(20)    NOT NULL,
    address        VARCHAR(255)   NOT NULL,
    latitude       NUMERIC(10,7)  NOT NULL,
    longitude      NUMERIC(10,7)  NOT NULL,
    capacity       INT            NOT NULL,
    manager        VARCHAR(50),
    contact        VARCHAR(50),
    shelter_status VARCHAR(20)    NOT NULL DEFAULT '운영중',
    note           TEXT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shelter_shelter_type  ON shelter (shelter_type);
CREATE INDEX idx_shelter_disaster_type ON shelter (disaster_type);
CREATE INDEX idx_shelter_status        ON shelter (shelter_status);
CREATE INDEX idx_shelter_location      ON shelter (latitude, longitude);
