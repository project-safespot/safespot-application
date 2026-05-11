package com.safespot.asyncworker.redis;

import java.time.Duration;

public final class RedisTtlConstants {

    private RedisTtlConstants() {}

    // cache-ttl.md 기준
    public static final Duration SHELTER_STATUS            = Duration.ofSeconds(300);

    public static final Duration DISASTER_DETAIL           = Duration.ofSeconds(3600);
    public static final Duration DISASTER_MESSAGES_RECENT  = Duration.ofSeconds(300);
    public static final Duration DISASTER_MESSAGE_CORE     = Duration.ofSeconds(300);
    public static final Duration DISASTER_MESSAGES_LIST    = Duration.ofSeconds(300);

    public static final Duration ENVIRONMENT_WEATHER       = Duration.ofSeconds(7200);
    public static final Duration ENVIRONMENT_AIR_QUALITY   = Duration.ofSeconds(7200);
    public static final Duration ENVIRONMENT_WEATHER_ALERT = Duration.ofSeconds(7200);
}
