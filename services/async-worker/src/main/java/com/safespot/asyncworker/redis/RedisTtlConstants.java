package com.safespot.asyncworker.redis;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class RedisTtlConstants {

    private RedisTtlConstants() {}

    // TTL은 freshness 보장 수단이 아니라 safety cap이다.
    // 최신성은 ingestion/update event 기반 regeneration이 담당한다.
    // cache-ttl.md 기준

    public static final Duration SHELTER_DETAIL            = Duration.ofSeconds(3600);
    public static final Duration SHELTER_STATUS            = Duration.ofSeconds(3600);
    // shelter:map:item / shelter:map:tile 는 persistent read model 이므로 writer에서 TTL을 사용하지 않는다.
    // 상수는 기존 테스트/호환성용으로 남겨 둔다.
    public static final Duration SHELTER_MAP_ITEM          = Duration.ofSeconds(3600);
    public static final Duration SHELTER_MAP_TILE          = Duration.ofSeconds(600);

    public static final Duration DISASTER_DETAIL           = Duration.ofSeconds(3600);
    public static final Duration DISASTER_MESSAGES_RECENT  = Duration.ofSeconds(3600);
    public static final Duration DISASTER_MESSAGE_CORE     = Duration.ofSeconds(3600);
    public static final Duration DISASTER_MESSAGES_LIST    = Duration.ofSeconds(3600);

    public static final Duration ENVIRONMENT_WEATHER       = Duration.ofSeconds(7200);
    public static final Duration ENVIRONMENT_AIR_QUALITY   = Duration.ofSeconds(7200);
    public static final Duration ENVIRONMENT_WEATHER_ALERT = Duration.ofSeconds(7200);

    // shelter/disaster cache: TTL 만료 시점 분산용 additive jitter 상한
    public static final Duration SHELTER_DISASTER_JITTER   = Duration.ofSeconds(120);

    /**
     * base TTL에 0~maxJitter 범위의 random offset을 더해 동시 만료를 분산한다.
     * effective TTL: [base, base + maxJitter]
     */
    public static Duration withAddedJitter(Duration base, Duration maxJitter) {
        long jitterMs = ThreadLocalRandom.current().nextLong(0, maxJitter.toMillis() + 1);
        return Duration.ofMillis(base.toMillis() + jitterMs);
    }

    /**
     * base TTL에 ±jitterFraction 범위의 random offset을 적용한다.
     * 여러 키가 동시에 SET될 때 만료 시점이 분산되어 thundering herd를 완화한다.
     * 최소 1,000ms가 보장된다.
     */
    public static Duration withJitter(Duration base, double jitterFraction) {
        long baseMs = base.toMillis();
        long maxJitterMs = (long) (baseMs * jitterFraction);
        long jitterMs = ThreadLocalRandom.current().nextLong(-maxJitterMs, maxJitterMs);
        return Duration.ofMillis(Math.max(1_000, baseMs + jitterMs));
    }
}
