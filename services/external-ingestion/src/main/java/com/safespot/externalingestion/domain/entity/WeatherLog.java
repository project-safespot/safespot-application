package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "weather_log",
    indexes = {
        @Index(name = "idx_weather_grid_dt", columnList = "nx, ny, forecast_dt DESC")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_weather_grid_base_forecast",
            columnNames = {"nx", "ny", "base_date", "base_time", "forecast_dt"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class WeatherLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "nx", nullable = false)
    private Integer nx;

    @Column(name = "ny", nullable = false)
    private Integer ny;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "base_time", nullable = false, length = 4)
    private String baseTime;

    @Column(name = "forecast_dt", nullable = false)
    private OffsetDateTime forecastDt;

    @Column(name = "tmp", precision = 4, scale = 1)
    private BigDecimal tmp;

    @Column(name = "sky", length = 10)
    private String sky;

    @Column(name = "pty", length = 10)
    private String pty;

    @Column(name = "pop")
    private Integer pop;

    @Column(name = "pcp", length = 20)
    private String pcp;

    @Column(name = "wsd", precision = 4, scale = 1)
    private BigDecimal wsd;

    @Column(name = "reh")
    private Integer reh;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt = OffsetDateTime.now();
}
