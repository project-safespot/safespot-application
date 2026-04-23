package com.safespot.apipublicread.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "air_quality_log")
@Getter
@NoArgsConstructor
public class AirQualityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    @Column(name = "measured_at", nullable = false)
    private OffsetDateTime measuredAt;

    @Column(name = "pm10")
    private Integer pm10;

    @Column(name = "pm10_grade")
    private String pm10Grade;

    @Column(name = "pm25")
    private Integer pm25;

    @Column(name = "pm25_grade")
    private String pm25Grade;

    @Column(name = "o3", precision = 4, scale = 3)
    private BigDecimal o3;

    @Column(name = "o3_grade")
    private String o3Grade;

    @Column(name = "khai_value")
    private Integer khaiValue;

    @Column(name = "khai_grade")
    private String khaiGrade;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt;

    @PrePersist
    void prePersist() {
        if (collectedAt == null) collectedAt = OffsetDateTime.now();
    }
}
