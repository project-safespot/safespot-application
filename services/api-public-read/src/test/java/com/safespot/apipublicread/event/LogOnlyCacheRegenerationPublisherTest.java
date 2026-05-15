package com.safespot.apipublicread.event;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogOnlyCacheRegenerationPublisherTest {

    @Mock CacheKeyFamilyResolver resolver;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void publish_supportedKey_completesWithoutException() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver, meterRegistry);

        assertThatCode(() -> publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_unsupportedKey_skipsAndDoesNotThrow() {
        when(resolver.resolve("unknown:key")).thenReturn(Optional.empty());
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver, meterRegistry);

        assertThatCode(() -> publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS, "/disaster-alerts"))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_supportedKey_resolvesFamily() {
        when(resolver.resolve("shelter:status:42")).thenReturn(Optional.of("shelter_status"));
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver, meterRegistry);

        publisher.publish("shelter:status:42", CacheRegenerationReason.REDIS_DOWN, "/shelters/{shelterId}");

        verify(resolver).resolve("shelter:status:42");
    }

    @Test
    void publishTarget_supportedTarget_completesWithoutException() {
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver, meterRegistry);

        assertThatCode(() -> publisher.publishTarget("SHELTER_GEO_INDEX", CacheRegenerationReason.CACHE_MISS, "/shelters/nearby"))
                .doesNotThrowAnyException();
    }
}
