package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Profile({"cache-worker", "async-worker"})
@Repository
@RequiredArgsConstructor
public class JdbcEvacuationEntryRepository implements EvacuationEntryRepository {

    private static final String COUNT_ENTERED_SQL =
        "SELECT COUNT(*) FROM evacuation_entry WHERE shelter_id = :shelterId AND entry_status = 'ENTERED'";

    private static final String COUNT_ENTERED_BY_SHELTER_IDS_SQL =
        "SELECT shelter_id, COUNT(*) AS occupancy " +
        "FROM evacuation_entry " +
        "WHERE shelter_id IN (:shelterIds) AND entry_status = 'ENTERED' " +
        "GROUP BY shelter_id";

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

    @Override
    public Map<Long, Integer> countEnteredByShelterIds(Collection<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> result = new HashMap<>();
        jdbcTemplate.query(
            COUNT_ENTERED_BY_SHELTER_IDS_SQL,
            Map.of("shelterIds", shelterIds),
            rs -> result.put(rs.getLong("shelter_id"), rs.getInt("occupancy"))
        );
        return result;
    }
}
