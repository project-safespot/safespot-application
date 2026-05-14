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

import java.util.List;

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

        List<Long> targetIds = payload.targetIds();
        if (targetIds != null && !targetIds.isEmpty()) {
            handleBatch(payload, envelope);
            return;
        }

        handleLegacy(payload, envelope);
    }

    private void handleBatch(CacheRegenerationRequestedPayload payload, EventEnvelope envelope) {
        String targetType = payload.targetType();
        List<Long> targetIds = payload.targetIds();
        String cacheKeyFamily = payload.cacheKeyFamily() != null ? payload.cacheKeyFamily() : "unknown";
        String schemaVersion = payload.schemaVersion() != null ? String.valueOf(payload.schemaVersion()) : "unknown";

        if (targetType == null) {
            log.warn("CacheRegenerationRequested batch: targetType is null, skipping: traceId={}", envelope.getTraceId());
            return;
        }

        log.info("Handling CacheRegenerationRequested batch (cache-worker): targetType={}, count={}, traceId={}",
            targetType, targetIds.size(), envelope.getTraceId());

        workerMetrics.incrementCacheRegenerationRequested(
            cacheKeyFamily, EventType.CacheRegenerationRequested.name(),
            payload.reason(), schemaVersion);

        try {
            if ("SHELTER_STATUS".equals(targetType)) {
                shelterStatusService.recalculateBatch(targetIds);
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }
            log.warn("CacheRegenerationRequested batch: unsupported targetType={}, traceId={}",
                targetType, envelope.getTraceId());
        } catch (Exception e) {
            workerMetrics.incrementCacheRegenerationFailed(cacheKeyFamily, e.getClass().getSimpleName());
            throw e;
        }
    }

    private void handleLegacy(CacheRegenerationRequestedPayload payload, EventEnvelope envelope) {
        String cacheKey = payload.cacheKey();
        String cacheKeyFamily = payload.cacheKeyFamily();
        String schemaVersion = payload.schemaVersion() != null ? String.valueOf(payload.schemaVersion()) : "unknown";

        if (cacheKey == null) {
            log.warn("CacheRegenerationRequested: cacheKey and targetIds both absent, skipping: traceId={}", envelope.getTraceId());
            return;
        }

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
