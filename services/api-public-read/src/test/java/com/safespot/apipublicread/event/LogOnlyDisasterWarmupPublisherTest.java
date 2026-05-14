package com.safespot.apipublicread.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class LogOnlyDisasterWarmupPublisherTest {

    @Test
    void publish_예외없이_완료() {
        LogOnlyDisasterWarmupPublisher publisher = new LogOnlyDisasterWarmupPublisher();
        assertThatNoException().isThrownBy(() -> publisher.publish(10, true));
    }

    // local/test profile 전용 검증 — dev/prod에서는 SqsDisasterWarmupPublisher 필수.
    // dev에서 SQS publisher bean이 없으면 DI 실패로 앱 시작이 실패하여 명확히 구분된다.
    @Test
    void LogOnlyPublisher_Profile_local_test_에만_활성화됨() {
        Profile profile = LogOnlyDisasterWarmupPublisher.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("local", "test");
    }

    @Test
    void LogOnlyPublisher_ConditionalOnProperty_log_matchIfMissing_true() {
        ConditionalOnProperty cond = LogOnlyDisasterWarmupPublisher.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(cond.name()).contains("safespot.cache-regeneration.publisher-mode");
        assertThat(cond.havingValue()).isEqualTo("log");
        // local에서 publisher-mode 미설정 시에도 fallback으로 동작
        assertThat(cond.matchIfMissing()).isTrue();
    }
}
