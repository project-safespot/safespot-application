package com.safespot.asyncworker.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeyConstantsTest {

    @Test
    void shelterDetail_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterDetail(101L))
            .isEqualTo("shelter:detail:101");
    }

    @Test
    void shelterMapItem_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterMapItem(101L))
            .isEqualTo("shelter:map:item:101");
    }

    @Test
    void shelterGeo_all_all_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterGeo("all", "all"))
            .isEqualTo("shelter:geo:seoul:all:all");
    }

    @Test
    void shelterGeo_disaster_all_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterGeo("FLOOD", "all"))
            .isEqualTo("shelter:geo:seoul:FLOOD:all");
    }

    @Test
    void shelterGeo_disaster_shelterType_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterGeo("FLOOD", "DESIGNATED"))
            .isEqualTo("shelter:geo:seoul:FLOOD:DESIGNATED");
    }

    @Test
    void shelterMapTile_key를_생성한다() {
        assertThat(RedisKeyConstants.shelterMapTile(13, 7285, 3172, "FLOOD", "DESIGNATED"))
            .isEqualTo("shelter:map:tile:13:7285:3172:FLOOD:DESIGNATED");
    }
}
