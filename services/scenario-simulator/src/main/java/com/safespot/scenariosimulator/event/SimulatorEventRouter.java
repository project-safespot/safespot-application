package com.safespot.scenariosimulator.event;

import com.safespot.scenariosimulator.config.SimulatorSqsProperties;
import com.safespot.scenariosimulator.event.payload.CacheRegenerationRequestedPayload;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimulatorEventRouter {

    private final SimulatorSqsProperties properties;

    public RoutedQueue resolve(EventEnvelope<?> envelope) {
        return switch (envelope.getEventType()) {
            case "EvacuationEntryCreated" -> RoutedQueue.cacheRefresh(properties.getCacheRefreshQueueUrl(), null);
            case "DisasterAlertCreated" -> RoutedQueue.readmodelRefresh(properties.getReadmodelRefreshQueueUrl(), null);
            case "CacheRegenerationRequested" -> resolveCacheRegeneration(envelope);
            default -> throw new IllegalArgumentException(
                    "Unsupported simulator SQS routing eventType=" + envelope.getEventType());
        };
    }

    private RoutedQueue resolveCacheRegeneration(EventEnvelope<?> envelope) {
        if (!(envelope.getPayload() instanceof CacheRegenerationRequestedPayload payload)) {
            throw new IllegalArgumentException(
                    "CacheRegenerationRequested payload type unsupported for simulator routing: eventId="
                            + envelope.getEventId());
        }

        String cacheKey = payload.getCacheKey();
        if (cacheKey == null || cacheKey.isBlank()) {
            throw new IllegalArgumentException(
                    "CacheRegenerationRequested cacheKey is blank: eventId=" + envelope.getEventId());
        }

        if (cacheKey.startsWith("shelter:")) {
            return RoutedQueue.cacheRefresh(properties.getCacheRefreshQueueUrl(), cacheKey);
        }
        if (cacheKey.startsWith("disaster:")) {
            return RoutedQueue.readmodelRefresh(properties.getReadmodelRefreshQueueUrl(), cacheKey);
        }

        throw new IllegalArgumentException(
                "Unsupported CacheRegenerationRequested cacheKey family: cacheKey=" + cacheKey);
    }

    @Getter
    public static final class RoutedQueue {
        private final String queueRole;
        private final String queueUrl;
        private final String queueName;
        private final String cacheKey;

        private RoutedQueue(String queueRole, String queueUrl, String cacheKey) {
            this.queueRole = queueRole;
            this.queueUrl = queueUrl == null ? "" : queueUrl;
            this.queueName = extractQueueName(queueUrl);
            this.cacheKey = cacheKey;
        }

        public static RoutedQueue cacheRefresh(String queueUrl, String cacheKey) {
            return new RoutedQueue("cache-refresh", queueUrl, cacheKey);
        }

        public static RoutedQueue readmodelRefresh(String queueUrl, String cacheKey) {
            return new RoutedQueue("readmodel-refresh", queueUrl, cacheKey);
        }

        private static String extractQueueName(String queueUrl) {
            if (queueUrl == null || queueUrl.isBlank()) {
                return "";
            }
            int idx = queueUrl.lastIndexOf('/');
            return idx >= 0 ? queueUrl.substring(idx + 1) : queueUrl;
        }
    }
}
