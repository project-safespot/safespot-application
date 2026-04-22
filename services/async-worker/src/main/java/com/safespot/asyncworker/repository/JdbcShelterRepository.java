package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcShelterRepository implements ShelterRepository {

    private static final String FIND_BY_ID_SQL =
        "SELECT shelter_id, capacity, shelter_status FROM shelter WHERE shelter_id = :shelterId";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<ShelterInfo> findById(Long shelterId) {
        try {
            ShelterInfo info = jdbcTemplate.queryForObject(
                FIND_BY_ID_SQL,
                Map.of("shelterId", shelterId),
                (rs, rowNum) -> new ShelterInfo(
                    rs.getLong("shelter_id"),
                    rs.getInt("capacity"),
                    rs.getString("shelter_status")
                )
            );
            return Optional.ofNullable(info);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
