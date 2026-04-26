package com.safespot.apipublicread.event;

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

    @Test
    void publish_supportedKey_completesWithoutException() {
        when(resolver.resolve("disaster:messages:list:seoul"))
                .thenReturn(Optional.of("disaster_messages_list"));
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver);

        assertThatCode(() -> publisher.publish("disaster:messages:list:seoul", CacheRegenerationReason.CACHE_MISS))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_unsupportedKey_skipsAndDoesNotThrow() {
        when(resolver.resolve("unknown:key")).thenReturn(Optional.empty());
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver);

        assertThatCode(() -> publisher.publish("unknown:key", CacheRegenerationReason.CACHE_MISS))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_supportedKey_resolvesFamily() {
        when(resolver.resolve("shelter:status:42")).thenReturn(Optional.of("shelter_status"));
        LogOnlyCacheRegenerationPublisher publisher = new LogOnlyCacheRegenerationPublisher(resolver);

        publisher.publish("shelter:status:42", CacheRegenerationReason.REDIS_DOWN);

        verify(resolver).resolve("shelter:status:42");
    }
}
