package com.safespot.asyncworker.redis;

public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    public static final String SHELTER_STATUS              = "shelter:status:%d";

    public static final String DISASTER_DETAIL             = "disaster:detail:%d";
    public static final String DISASTER_MESSAGES_RECENT    = "disaster:messages:recent:seoul";
    public static final String DISASTER_MESSAGE_CORE       = "disaster:message:core:seoul";
    public static final String DISASTER_MESSAGES_LIST      = "disaster:messages:list:seoul";

    public static final String ENVIRONMENT_WEATHER         = "environment:weather:seoul";
    public static final String ENVIRONMENT_AIR_QUALITY     = "environment:air-quality:seoul";
    public static final String ENVIRONMENT_WEATHER_ALERT   = "environment:weather-alert:seoul";

    public static final String IDEMPOTENCY                 = "idempotency:%s";

    public static String shelterStatus(Long shelterId) {
        return SHELTER_STATUS.formatted(shelterId);
    }

    public static String disasterDetail(Long alertId) {
        return DISASTER_DETAIL.formatted(alertId);
    }

    public static String idempotency(String idempotencyKey) {
        return IDEMPOTENCY.formatted(idempotencyKey);
    }
}
