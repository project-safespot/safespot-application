package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
@RequiredArgsConstructor
public class JdbcEvacuationEntryRepository implements EvacuationEntryRepository {

    private static final String COUNT_ENTERED_SQL =
        "SELECT COUNT(*) FROM evacuation_entry WHERE shelter_id = :shelterId AND entry_status = 'ENTERED'";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public int countEntered(Long shelterId) {
        Integer count = jdbcTemplate.queryForObject(
            COUNT_ENTERED_SQL,
            Map.of("shelterId", shelterId),
            Integer.class
        );
        return (count != null) ? count : 0;
    }
}
