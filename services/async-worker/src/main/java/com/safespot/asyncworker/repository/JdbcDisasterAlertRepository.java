package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("readmodel-worker")
@Repository
@RequiredArgsConstructor
public class JdbcDisasterAlertRepository implements DisasterAlertRepository {

    private static final String FIND_ACTIVE_BY_REGION_SQL = """
        SELECT alert_id, disaster_type, region, level, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at
        FROM disaster_alert
        WHERE region = :region AND expired_at IS NULL
        ORDER BY issued_at DESC
        """;

    private static final String FIND_BY_REGION_AND_TYPE_SQL = """
        SELECT alert_id, disaster_type, region, level, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at
        FROM disaster_alert
        WHERE region = :region AND disaster_type = :disasterType
        ORDER BY issued_at DESC
        """;

    private static final String FIND_LATEST_ACTIVE_BY_TYPE_AND_REGION_SQL = """
        SELECT alert_id, disaster_type, region, level, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at
        FROM disaster_alert
        WHERE disaster_type = :disasterType
          AND region = :region
          AND expired_at IS NULL
        ORDER BY issued_at DESC
        LIMIT 1
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT alert_id, disaster_type, region, level, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at
        FROM disaster_alert
        WHERE alert_id = :alertId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<DisasterAlertRecord> findActiveByRegion(String region) {
        return jdbcTemplate.query(
            FIND_ACTIVE_BY_REGION_SQL,
            Map.of("region", region),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public List<DisasterAlertRecord> findByRegionAndDisasterType(String region, String disasterType) {
        return jdbcTemplate.query(
            FIND_BY_REGION_AND_TYPE_SQL,
            Map.of("region", region, "disasterType", disasterType),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public Optional<DisasterAlertRecord> findLatestActiveByTypeAndRegion(String disasterType, String region) {
        try {
            DisasterAlertRecord record = jdbcTemplate.queryForObject(
                FIND_LATEST_ACTIVE_BY_TYPE_AND_REGION_SQL,
                Map.of("disasterType", disasterType, "region", region),
                (rs, rowNum) -> mapRow(rs)
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<DisasterAlertRecord> findById(Long alertId) {
        try {
            DisasterAlertRecord record = jdbcTemplate.queryForObject(
                FIND_BY_ID_SQL,
                Map.of("alertId", alertId),
                (rs, rowNum) -> mapRow(rs)
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private DisasterAlertRecord mapRow(ResultSet rs) throws SQLException {
        return new DisasterAlertRecord(
            rs.getLong("alert_id"),
            rs.getString("disaster_type"),
            rs.getString("region"),
            rs.getString("level"),
            rs.getString("message"),
            rs.getString("source"),
            rs.getString("issued_at"),
            rs.getString("expired_at")
        );
    }
}
