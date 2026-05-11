package com.safespot.apipublicread.dto.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCacheDtoCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void disasterMessagesList_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                [
                  {
                    "schemaVersion": 1,
                    "alertId": 55,
                    "disasterType": "EARTHQUAKE",
                    "rawType": "지진",
                    "messageCategory": "ALERT",
                    "level": "주의",
                    "levelRank": 2,
                    "region": "서울특별시",
                    "issuedAt": "2026-04-14T08:55:00+09:00",
                    "expiredAt": null,
                    "message": "지진 감지",
                    "source": "MOIS",
                    "isInScope": true,
                    "writerOnlyField": "ignored"
                  }
                ]
                """;

        List<DisasterMessageCacheDto> result =
                objectMapper.readValue(json, new TypeReference<>() {});

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alertId()).isEqualTo(55L);
        assertThat(result.get(0).messageCategory()).isEqualTo("ALERT");
    }

    @Test
    void disasterDetail_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "alertId": 55,
                  "disasterType": "EARTHQUAKE",
                  "rawType": "지진",
                  "messageCategory": "ALERT",
                  "level": "주의",
                  "levelRank": 2,
                  "region": "서울특별시",
                  "issuedAt": "2026-04-14T08:55:00+09:00",
                  "expiredAt": null,
                  "message": "지진 감지",
                  "source": "MOIS",
                  "isInScope": true,
                  "writerOnlyField": "ignored"
                }
                """;

        DisasterDetailCacheDto result = objectMapper.readValue(json, DisasterDetailCacheDto.class);

        assertThat(result.alertId()).isEqualTo(55L);
        assertThat(result.rawType()).isEqualTo("지진");
    }

    @Test
    void shelterStatus_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                {
                  "currentOccupancy": 68,
                  "availableCapacity": 52,
                  "congestionLevel": "NORMAL",
                  "shelterStatus": "OPERATING",
                  "writerOnlyField": "ignored"
                }
                """;

        ShelterStatusCacheDto result = objectMapper.readValue(json, ShelterStatusCacheDto.class);

        assertThat(result.currentOccupancy()).isEqualTo(68);
        assertThat(result.availableCapacity()).isEqualTo(52);
    }

    @Test
    void environmentWeather_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                {
                  "nx": 60,
                  "ny": 127,
                  "temperature": 18.5,
                  "weatherCondition": "맑음",
                  "precipitationType": "없음",
                  "precipitation": "0mm",
                  "windSpeed": 2.4,
                  "humidity": 45,
                  "forecastedAt": "2026-04-15T15:00:00+09:00",
                  "writerOnlyField": "ignored"
                }
                """;

        WeatherCacheDto result = objectMapper.readValue(json, WeatherCacheDto.class);

        assertThat(result.nx()).isEqualTo(60);
        assertThat(result.windSpeed()).isEqualByComparingTo("2.4");
    }

    @Test
    void environmentAirQuality_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                {
                  "stationName": "종로구",
                  "aqi": 42,
                  "grade": "좋음",
                  "pm10": 31,
                  "pm10Grade": "좋음",
                  "pm25": 16,
                  "pm25Grade": "보통",
                  "o3": 0.033,
                  "o3Grade": "보통",
                  "measuredAt": "2026-04-15T15:00:00+09:00",
                  "writerOnlyField": "ignored"
                }
                """;

        AirQualityCacheDto result = objectMapper.readValue(json, AirQualityCacheDto.class);

        assertThat(result.stationName()).isEqualTo("종로구");
        assertThat(result.o3()).isEqualByComparingTo("0.033");
    }

    @Test
    void environmentWeatherAlert_parsesAsyncWorkerPayload() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "status": "no_data",
                  "alerts": [],
                  "writerOnlyField": "ignored"
                }
                """;

        WeatherAlertCacheDto result = objectMapper.readValue(json, WeatherAlertCacheDto.class);

        assertThat(result.status()).isEqualTo("no_data");
        assertThat(result.alerts()).isEmpty();
    }
}
