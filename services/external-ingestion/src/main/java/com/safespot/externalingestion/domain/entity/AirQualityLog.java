package com.safespot.externalingestion.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "air_quality_log",
    indexes = {
        @Index(name = "idx_air_station_measured", columnList = "station_name, measured_at DESC")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_air_station_measured_at",
            columnNames = {"station_name", "measured_at"})
    }
)
@Getter
@Setter
@NoArgsConstructor
public class AirQualityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "station_name", nullable = false, length = 50)
    private String stationName;

    @Column(name = "measured_at", nullable = false)
    private OffsetDateTime measuredAt;

    @Column(name = "pm10")
    private Integer pm10;

    @Column(name = "pm10_grade", length = 10)
    private String pm10Grade;

    @Column(name = "pm25")
    private Integer pm25;

    @Column(name = "pm25_grade", length = 10)
    private String pm25Grade;

    @Column(name = "o3", precision = 4, scale = 3)
    private BigDecimal o3;

    @Column(name = "o3_grade", length = 10)
    private String o3Grade;

    @Column(name = "khai_value")
    private Integer khaiValue;

    @Column(name = "khai_grade", length = 10)
    private String khaiGrade;

    @Column(name = "collected_at", nullable = false)
    private OffsetDateTime collectedAt = OffsetDateTime.now();
}
