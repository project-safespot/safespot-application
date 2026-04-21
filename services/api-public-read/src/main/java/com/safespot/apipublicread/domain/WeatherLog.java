package com.safespot.apipublicread.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "weather_log")
@Getter
@NoArgsConstructor
public class WeatherLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "nx", nullable = false)
    private int nx;

    @Column(name = "ny", nullable = false)
    private int ny;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "base_time", nullable = false)
    private String baseTime;

    @Column(name = "forecast_dt", nullable = false)
    private OffsetDateTime forecastDt;

    @Column(name = "tmp", precision = 4, scale = 1)
    private BigDecimal tmp;

    @Column(name = "sky")
    private String sky;

    @Column(name = "pty")
    private String pty;

    @Column(name = "pop")
    private Integer pop;

    @Column(name = "pcp")
    private String pcp;

    @Column(name = "wsd", precision = 4, scale = 1)
    private BigDecimal wsd;

    @Column(name = "reh")
    private Integer reh;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    @PrePersist
    void prePersist() {
        if (collectedAt == null) collectedAt = OffsetDateTime.now();
    }
}
