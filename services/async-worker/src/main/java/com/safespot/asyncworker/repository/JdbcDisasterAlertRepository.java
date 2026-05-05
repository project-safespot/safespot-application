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

@Profile({"readmodel-worker", "async-worker"})
@Repository
@RequiredArgsConstructor
public class JdbcDisasterAlertRepository implements DisasterAlertRepository {

    private static final String FIND_BY_ID_SQL = """
        SELECT alert_id, disaster_type, raw_type, message_category,
               region, level, level_rank, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at,
               is_in_scope
        FROM disaster_alert
        WHERE alert_id = :alertId
        """;

    // is_in_scope=true인 record를 issuedAt DESC 순서로 최대 limit건 반환
    private static final String FIND_IN_SCOPE_SQL = """
        SELECT alert_id, disaster_type, raw_type, message_category,
               region, level, level_rank, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at,
               is_in_scope
        FROM disaster_alert
        WHERE is_in_scope = true
        ORDER BY issued_at DESC
        LIMIT :limit
        """;

    // core message 선택 조건: isInScope=true, levelRank>=3, messageCategory!=CLEAR, issuedAt DESC, limit 1
    private static final String FIND_CORE_MESSAGE_SQL = """
        SELECT alert_id, disaster_type, raw_type, message_category,
               region, level, level_rank, message, source,
               issued_at::text AS issued_at,
               expired_at::text AS expired_at,
               is_in_scope
        FROM disaster_alert
        WHERE is_in_scope = true
          AND level_rank >= 3
          AND message_category != 'CLEAR'
        ORDER BY issued_at DESC
        LIMIT 1
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

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

    @Override
    public List<DisasterAlertRecord> findInScopeOrderByIssuedAtDesc(int limit) {
        return jdbcTemplate.query(
            FIND_IN_SCOPE_SQL,
            Map.of("limit", limit),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public Optional<DisasterAlertRecord> findCoreMessage() {
        try {
            DisasterAlertRecord record = jdbcTemplate.queryForObject(
                FIND_CORE_MESSAGE_SQL,
                Map.of(),
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
            rs.getString("raw_type"),
            rs.getString("message_category"),
            rs.getString("region"),
            rs.getString("level"),
            (Integer) rs.getObject("level_rank"),
            rs.getString("message"),
            rs.getString("source"),
            rs.getString("issued_at"),
            rs.getString("expired_at"),
            rs.getObject("is_in_scope", Boolean.class)
        );
    }
}
