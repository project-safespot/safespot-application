package com.safespot.apipublicread.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "safespot.public-read.fallback")
public class FallbackControlProperties {

    private Duration defaultLockTtl = Duration.ofSeconds(5);
    private Duration shelterMapTileLockTtl = Duration.ofSeconds(5);
    private Duration disasterDetailLockTtl = Duration.ofSeconds(5);
    private Duration shelterStatusLockTtl = Duration.ofSeconds(3);
    private Duration shelterMapItemLockTtl = Duration.ofSeconds(3);
    private Duration shelterMapTileNegativeTtl = Duration.ofSeconds(20);
    private Duration regenerationSuppressTtl = Duration.ofSeconds(45);
    private Duration followerBackoff = Duration.ofMillis(50);
    private int batchChunkSize = 50;

    public Duration lockTtl(String cache) {
        return switch (cache) {
            case "shelter_map_tile" -> shelterMapTileLockTtl;
            case "disaster_detail" -> disasterDetailLockTtl;
            case "shelter_status" -> shelterStatusLockTtl;
            case "shelter_map_item" -> shelterMapItemLockTtl;
            default -> defaultLockTtl;
        };
    }

    public Duration getDefaultLockTtl() {
        return defaultLockTtl;
    }

    public void setDefaultLockTtl(Duration defaultLockTtl) {
        this.defaultLockTtl = defaultLockTtl;
    }

    public Duration getShelterMapTileLockTtl() {
        return shelterMapTileLockTtl;
    }

    public void setShelterMapTileLockTtl(Duration shelterMapTileLockTtl) {
        this.shelterMapTileLockTtl = shelterMapTileLockTtl;
    }

    public Duration getDisasterDetailLockTtl() {
        return disasterDetailLockTtl;
    }

    public void setDisasterDetailLockTtl(Duration disasterDetailLockTtl) {
        this.disasterDetailLockTtl = disasterDetailLockTtl;
    }

    public Duration getShelterStatusLockTtl() {
        return shelterStatusLockTtl;
    }

    public void setShelterStatusLockTtl(Duration shelterStatusLockTtl) {
        this.shelterStatusLockTtl = shelterStatusLockTtl;
    }

    public Duration getShelterMapItemLockTtl() {
        return shelterMapItemLockTtl;
    }

    public void setShelterMapItemLockTtl(Duration shelterMapItemLockTtl) {
        this.shelterMapItemLockTtl = shelterMapItemLockTtl;
    }

    public Duration getShelterMapTileNegativeTtl() {
        return shelterMapTileNegativeTtl;
    }

    public void setShelterMapTileNegativeTtl(Duration shelterMapTileNegativeTtl) {
        this.shelterMapTileNegativeTtl = shelterMapTileNegativeTtl;
    }

    public Duration getRegenerationSuppressTtl() {
        return regenerationSuppressTtl;
    }

    public void setRegenerationSuppressTtl(Duration regenerationSuppressTtl) {
        this.regenerationSuppressTtl = regenerationSuppressTtl;
    }

    public Duration getFollowerBackoff() {
        return followerBackoff;
    }

    public void setFollowerBackoff(Duration followerBackoff) {
        this.followerBackoff = followerBackoff;
    }

    public int getBatchChunkSize() {
        return batchChunkSize;
    }

    public void setBatchChunkSize(int batchChunkSize) {
        this.batchChunkSize = batchChunkSize;
    }
}
