package com.safespot.asyncworker.redis;

public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    public static final String SHELTER_STATUS    = "shelter:status:%d";
    public static final String DISASTER_ACTIVE   = "disaster:active:%s";
    public static final String DISASTER_ALERT_LIST  = "disaster:alert:list:%s:%s";
    public static final String DISASTER_DETAIL   = "disaster:detail:%d";
    public static final String DISASTER_LATEST   = "disaster:latest:%s:%s";
    public static final String ENV_WEATHER       = "env:weather:%d:%d";
    public static final String ENV_AIR           = "env:air:%s";
    public static final String IDEMPOTENCY       = "idempotency:%s";

    public static String shelterStatus(Long shelterId) {
        return SHELTER_STATUS.formatted(shelterId);
    }

    public static String disasterActive(String region) {
        return DISASTER_ACTIVE.formatted(region);
    }

    public static String disasterAlertList(String region, String disasterType) {
        return DISASTER_ALERT_LIST.formatted(region, disasterType);
    }

    public static String disasterDetail(Long alertId) {
        return DISASTER_DETAIL.formatted(alertId);
    }

    public static String disasterLatest(String disasterType, String region) {
        return DISASTER_LATEST.formatted(disasterType, region);
    }

    public static String envWeather(int nx, int ny) {
        return ENV_WEATHER.formatted(nx, ny);
    }

    public static String envAir(String stationName) {
        return ENV_AIR.formatted(stationName);
    }

    public static String idempotency(String idempotencyKey) {
        return IDEMPOTENCY.formatted(idempotencyKey);
    }
}
