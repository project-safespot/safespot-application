package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile({"cache-worker", "async-worker"})
@Repository
@RequiredArgsConstructor
public class JdbcShelterRepository implements ShelterRepository {

    private static final String FIND_BY_ID_SQL =
        "SELECT shelter_id, capacity, shelter_status FROM shelter WHERE shelter_id = :shelterId";

    private static final String FIND_ALL_FOR_STATUS_WARMUP_SQL =
        "SELECT shelter_id, capacity, shelter_status FROM shelter";

    private static final String FIND_BY_IDS_SQL =
        "SELECT shelter_id, capacity, shelter_status FROM shelter WHERE shelter_id IN (:shelterIds)";

    private static final String MAP_READ_MODEL_SELECT =
        "SELECT shelter_id, name, shelter_type, disaster_type, address, capacity, latitude, longitude, updated_at FROM shelter";

    private static final String FIND_ALL_FOR_MAP_READ_MODEL_SQL =
        MAP_READ_MODEL_SELECT;

    private static final String FIND_BY_IDS_FOR_MAP_ITEMS_SQL =
        MAP_READ_MODEL_SELECT + " WHERE shelter_id IN (:shelterIds)";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public Optional<ShelterInfo> findById(Long shelterId) {
        try {
            ShelterInfo info = jdbcTemplate.queryForObject(
                FIND_BY_ID_SQL,
                Map.of("shelterId", shelterId),
                (rs, rowNum) -> new ShelterInfo(
                    rs.getLong("shelter_id"),
                    rs.getObject("capacity", Integer.class),
                    rs.getString("shelter_status")
                )
            );
            return Optional.ofNullable(info);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ShelterInfo> findAllForStatusWarmup() {
        return jdbcTemplate.query(
            FIND_ALL_FOR_STATUS_WARMUP_SQL,
            (rs, rowNum) -> new ShelterInfo(
                rs.getLong("shelter_id"),
                rs.getObject("capacity", Integer.class),
                rs.getString("shelter_status")
            )
        );
    }

    @Override
    public List<ShelterInfo> findByIds(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
            FIND_BY_IDS_SQL,
            Map.of("shelterIds", shelterIds),
            (rs, rowNum) -> new ShelterInfo(
                rs.getLong("shelter_id"),
                rs.getObject("capacity", Integer.class),
                rs.getString("shelter_status")
            )
        );
    }

    @Override
    public List<ShelterMapSource> findAllForMapReadModel() {
        return jdbcTemplate.query(FIND_ALL_FOR_MAP_READ_MODEL_SQL, (rs, rowNum) -> mapShelterMapSource(rs));
    }

    @Override
    public List<ShelterMapSource> findByIdsForMapItems(List<Long> shelterIds) {
        if (shelterIds == null || shelterIds.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
            FIND_BY_IDS_FOR_MAP_ITEMS_SQL,
            Map.of("shelterIds", shelterIds),
            (rs, rowNum) -> mapShelterMapSource(rs)
        );
    }

    private ShelterMapSource mapShelterMapSource(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShelterMapSource(
            rs.getLong("shelter_id"),
            rs.getString("name"),
            rs.getString("shelter_type"),
            rs.getString("disaster_type"),
            rs.getString("address"),
            rs.getObject("capacity", Integer.class),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getObject("updated_at", java.time.OffsetDateTime.class)
        );
    }
}
