package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.CacheRegenerationRequestedPayload;
import com.safespot.asyncworker.metrics.WorkerMetrics;
import com.safespot.asyncworker.redis.RedisKeyConstants;
import com.safespot.asyncworker.service.disaster.DisasterReadModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("readmodel-worker")
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheRegenerationReadModelWorkerHandler implements EventHandler {

    private static final String DISASTER_DETAIL_PREFIX = "disaster:detail:";

    private final DisasterReadModelService disasterReadModelService;
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

        log.info("Handling CacheRegenerationRequested (readmodel-worker): cacheKey={}, cacheKeyFamily={}, traceId={}",
            cacheKey, cacheKeyFamily, envelope.getTraceId());

        workerMetrics.incrementCacheRegenerationRequested(
            cacheKeyFamily, EventType.CacheRegenerationRequested.name(),
            payload.reason(), schemaVersion);

        try {
            if (RedisKeyConstants.DISASTER_MESSAGES_RECENT.equals(cacheKey)) {
                disasterReadModelService.rebuildRecent();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (RedisKeyConstants.DISASTER_MESSAGE_CORE.equals(cacheKey)) {
                disasterReadModelService.rebuildCore();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (RedisKeyConstants.DISASTER_MESSAGES_LIST.equals(cacheKey)) {
                disasterReadModelService.rebuildList();
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            if (cacheKey.startsWith(DISASTER_DETAIL_PREFIX)) {
                String idStr = cacheKey.substring(DISASTER_DETAIL_PREFIX.length());
                Long alertId = parseId(idStr, cacheKey);
                disasterReadModelService.rebuildDetail(alertId);
                workerMetrics.incrementCacheRegenerationCompleted(cacheKeyFamily);
                return;
            }

            log.warn("CacheRegenerationRequested: unhandled cacheKey for readmodel-worker, no-op: cacheKey={}, traceId={}",
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
                "CacheRegenerationRequested: invalid cacheKey format for readmodel-worker: " + cacheKey);
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
