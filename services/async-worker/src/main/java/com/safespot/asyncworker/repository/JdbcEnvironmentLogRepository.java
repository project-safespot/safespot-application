package com.safespot.asyncworker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Profile("cache-worker")
@Repository
@RequiredArgsConstructor
public class JdbcEnvironmentLogRepository implements EnvironmentLogRepository {

    // timeWindow 기준 1시간 단위 윈도우 내 최신 레코드 per (nx, ny)
    private static final String LATEST_WEATHER_SQL = """
        SELECT DISTINCT ON (nx, ny)
            nx, ny,
            COALESCE(tmp, 0.0)   AS tmp,
            COALESCE(sky, '')    AS sky,
            forecast_dt
        FROM weather_log
        WHERE collected_at >= :start AND collected_at < :end
        ORDER BY nx, ny, collected_at DESC
        """;

    // timeWindow 기준 1시간 단위 윈도우 내 최신 레코드 per station_name
    private static final String LATEST_AIR_QUALITY_SQL = """
        SELECT DISTINCT ON (station_name)
            station_name,
            COALESCE(khai_value, 0)    AS khai_value,
            COALESCE(khai_grade, '')   AS khai_grade,
            measured_at
        FROM air_quality_log
        WHERE measured_at >= :start AND measured_at < :end
        ORDER BY station_name, measured_at DESC
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
