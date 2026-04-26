package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("cache-worker")
@Repository
@RequiredArgsConstructor
public class JdbcEnvironmentLogRepository implements EnvironmentLogRepository {

    // CacheRegenerationRequested 처리용 — collected_at DESC 기준 전역 최신 단일 레코드
    private static final String MOST_RECENT_WEATHER_SQL = """
        SELECT nx, ny,
            COALESCE(tmp, 0.0)   AS tmp,
            COALESCE(sky, '')    AS sky,
            forecast_dt
        FROM weather_log
        ORDER BY collected_at DESC
        LIMIT 1
        """;

    private static final String MOST_RECENT_AIR_QUALITY_SQL = """
        SELECT station_name,
            COALESCE(khai_value, 0)    AS khai_value,
            COALESCE(khai_grade, '')   AS khai_grade,
            measured_at
        FROM air_quality_log
        ORDER BY measured_at DESC
        LIMIT 1
        """;

    // timeWindow 기준 1시간 단위 윈도우 내 collected_at DESC 기준 최신 단일 레코드
    private static final String LATEST_WEATHER_SQL = """
        SELECT nx, ny,
            COALESCE(tmp, 0.0)   AS tmp,
            COALESCE(sky, '')    AS sky,
            forecast_dt
        FROM weather_log
        WHERE collected_at >= :start AND collected_at < :end
        ORDER BY collected_at DESC
        LIMIT 1
        """;

    // timeWindow 기준 1시간 단위 윈도우 내 measured_at DESC 기준 최신 단일 레코드
    private static final String LATEST_AIR_QUALITY_SQL = """
        SELECT station_name,
            COALESCE(khai_value, 0)    AS khai_value,
            COALESCE(khai_grade, '')   AS khai_grade,
            measured_at
        FROM air_quality_log
        WHERE measured_at >= :start AND measured_at < :end
        ORDER BY measured_at DESC
        LIMIT 1
        """;

    private static final DateTimeFormatter TIME_WINDOW_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<WeatherLogRecord> findLatestWeatherByTimeWindow(String timeWindow) {
        LocalDateTime start = LocalDateTime.parse(timeWindow, TIME_WINDOW_FORMATTER);
        LocalDateTime end = start.plusHours(1);

        return jdbcTemplate.query(
            LATEST_WEATHER_SQL,
            Map.of("start", start, "end", end),
            (rs, rowNum) -> new WeatherLogRecord(
                rs.getInt("nx"),
                rs.getInt("ny"),
                rs.getDouble("tmp"),
                mapSkyCondition(rs.getString("sky")),
                rs.getString("forecast_dt")
            )
        );
    }

    @Override
    public List<AirQualityLogRecord> findLatestAirQualityByTimeWindow(String timeWindow) {
        LocalDateTime start = LocalDateTime.parse(timeWindow, TIME_WINDOW_FORMATTER);
        LocalDateTime end = start.plusHours(1);

        return jdbcTemplate.query(
            LATEST_AIR_QUALITY_SQL,
            Map.of("start", start, "end", end),
            (rs, rowNum) -> new AirQualityLogRecord(
                rs.getString("station_name"),
                rs.getInt("khai_value"),
                rs.getString("khai_grade"),
                rs.getString("measured_at")
            )
        );
    }

    @Override
    public Optional<WeatherLogRecord> findMostRecentWeather() {
        try {
            WeatherLogRecord record = jdbcTemplate.queryForObject(
                MOST_RECENT_WEATHER_SQL,
                Map.of(),
                (rs, rowNum) -> new WeatherLogRecord(
                    rs.getInt("nx"),
                    rs.getInt("ny"),
                    rs.getDouble("tmp"),
                    mapSkyCondition(rs.getString("sky")),
                    rs.getString("forecast_dt")
                )
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AirQualityLogRecord> findMostRecentAirQuality() {
        try {
            AirQualityLogRecord record = jdbcTemplate.queryForObject(
                MOST_RECENT_AIR_QUALITY_SQL,
                Map.of(),
                (rs, rowNum) -> new AirQualityLogRecord(
                    rs.getString("station_name"),
                    rs.getInt("khai_value"),
                    rs.getString("khai_grade"),
                    rs.getString("measured_at")
                )
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // 기상청 sky 코드 → 사용자 표시 문자열 변환
    private String mapSkyCondition(String sky) {
        if (sky == null) return "";
        return switch (sky.trim()) {
            case "1" -> "맑음";
            case "3" -> "구름조금";
            case "4" -> "흐림";
            default -> sky;
        };
    }
}
