package com.safespot.apipublicread;

import com.safespot.apipublicread.cache.FallbackControlProperties;
import com.safespot.apipublicread.event.CacheRegenerationPublisher;
import com.safespot.apipublicread.event.SqsCacheRegenerationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:apipublicread;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.profiles.active=dev",
        "safespot.cache-regeneration.publisher-mode=sqs",
        "safespot.aws.sqs.cache-refresh-queue-url=",
        "safespot.aws.sqs.readmodel-refresh-queue-url=",
        "safespot.aws.sqs.environment-cache-refresh-queue-url="
})
class ApiPublicReadApplicationContextTest {

    @Autowired
    private FallbackControlProperties fallbackControlProperties;

    @Autowired
    private CacheRegenerationPublisher cacheRegenerationPublisher;

    @Test
    void contextLoadsWithSqsPublisherModeAndMissingQueueUrls() {
        assertThat(fallbackControlProperties.getDefaultLockTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(fallbackControlProperties.getFollowerBackoff()).isEqualTo(Duration.ofMillis(50));
        assertThat(cacheRegenerationPublisher).isInstanceOf(SqsCacheRegenerationPublisher.class);
    }
}
