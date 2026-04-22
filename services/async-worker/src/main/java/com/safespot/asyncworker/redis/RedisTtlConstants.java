package com.safespot.asyncworker.redis;

import java.time.Duration;

public final class RedisTtlConstants {

    private RedisTtlConstants() {}

    public static final Duration SHELTER_STATUS       = Duration.ofSeconds(30);
    public static final Duration DISASTER_ACTIVE      = Duration.ofMinutes(2);
    public static final Duration DISASTER_ALERT_LIST  = Duration.ofMinutes(5);
    public static final Duration DISASTER_DETAIL      = Duration.ofMinutes(10);
    public static final Duration ENV_WEATHER          = Duration.ofMinutes(120);
    public static final Duration ENV_AIR              = Duration.ofMinutes(120);
}
