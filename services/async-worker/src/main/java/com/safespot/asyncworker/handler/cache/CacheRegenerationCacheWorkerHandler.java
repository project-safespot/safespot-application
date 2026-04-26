package com.safespot.asyncworker.handler.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.payload.CacheRegenerationRequestedPayload;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.environment.EnvironmentCacheService;
import com.safespot.asyncworker.service.shelter.ShelterStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("cache-worker")
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheRegenerationCacheWorkerHandler implements EventHandler {

    private static final String SHELTER_STATUS_PREFIX = "shelter:status:";

    private final ShelterStatusService shelterStatusService;
    private final EnvironmentCacheService environmentCacheService;
    private final ObjectMapper objectMapper;
    private final WorkerMetrics workerMetrics;

    @Override
    public EventType supportedEventType() {
        return EventType.CacheRegenerationRequested;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        CacheRegenerationRequestedPayload payload = parsePayload(envelope);
        String cacheKey = payload.cacheKey();
        String cacheKeyFamily = payload.cacheKeyFamily();
        String schemaVersion = payload.schemaVersion() != null ? String.valueOf(payload.schemaVersion()) : "unknown";

        log.info("Handling CacheRegenerationRequested (cache-worker): cacheKey={}, cacheKeyFamily={}, traceId={}",
            cacheKey, cacheKeyFamily, envelope.getTraceId());

        workerMetrics.incrementCacheRegenerationRequested(
            cacheKeyFamily, EventType.CacheRegenerationRequested.name(),
            payload.reason(), schemaVersion);

        try {
            if (cacheKey.startsWith(SHELTER_STATUS_PREFIX)) {
                String idStr = cacheKey.substring(SHELTER_STATUS_PREFIX.length());
                Long shelterId = parseId(idStr, cacheKey);
                shelterStatusService.recalculate(shelterId);
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (RedisKeyConstants.ENVIRONMENT_WEATHER.equals(cacheKey)) {
                environmentCacheService.rebuildWeatherCache();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (RedisKeyConstants.ENVIRONMENT_AIR_QUALITY.equals(cacheKey)) {
                environmentCacheService.rebuildAirQualityCache();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (RedisKeyConstants.ENVIRONMENT_WEATHER_ALERT.equals(cacheKey)) {
                environmentCacheService.rebuildWeatherAlertCache();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            log.warn("CacheRegenerationRequested: unhandled cacheKey for cache-worker, no-op: cacheKey={}, traceId={}",
                cacheKey, envelope.getTraceId());

        } catch (Exception e) {
            workerMetrics.incrementCacheRegenerationFailed(cacheKeyFamily, e.getClass().getSimpleName());
            throw e;
        }
    }

    private Long parseId(String idStr, String cacheKey) {
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            throw new EventProcessingException(
                "CacheRegenerationRequested: invalid cacheKey format for cache-worker: " + cacheKey);
        }
    }

    private CacheRegenerationRequestedPayload parsePayload(EventEnvelope envelope) {
        try {
            return objectMapper.treeToValue(envelope.getPayload(), CacheRegenerationRequestedPayload.class);
        } catch (Exception e) {
            throw new EventProcessingException(
                "Failed to parse CacheRegenerationRequested payload: eventId=" + envelope.getEventId(), e);
        }
    }
}
