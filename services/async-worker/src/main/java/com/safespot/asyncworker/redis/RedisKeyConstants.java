package com.safespot.asyncworker.redis;

public final class RedisKeyConstants {

    private RedisKeyConstants() {}

    public static final String SHELTER_STATUS              = "shelter:status:%d";
    public static final String SHELTER_MAP_ITEM            = "shelter:map:item:%d";
    public static final String SHELTER_GEO                 = "shelter:geo:seoul:%s:%s";
    public static final String SHELTER_MAP_TILE            = "shelter:map:tile:%d:%d:%d:%s:%s";

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

    public static String shelterMapItem(Long shelterId) {
        return SHELTER_MAP_ITEM.formatted(shelterId);
    }

    public static String shelterGeo(String disasterType, String shelterType) {
        return SHELTER_GEO.formatted(disasterType, shelterType);
    }

    public static String shelterMapTile(int z, int x, int y, String disasterType, String shelterType) {
        return SHELTER_MAP_TILE.formatted(z, x, y, disasterType, shelterType);
    }

    public static String disasterDetail(Long alertId) {
        return DISASTER_DETAIL.formatted(alertId);
    }

    public static String idempotency(String idempotencyKey) {
        return IDEMPOTENCY.formatted(idempotencyKey);
    }
}
