package com.safespot.asyncworker.handler.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safespot.asyncworker.envelope.EventEnvelope;
import com.safespot.asyncworker.envelope.EventType;
import com.safespot.asyncworker.exception.EventProcessingException;
import com.safespot.asyncworker.handler.EventHandler;
import com.safespot.asyncworker.payload.CacheRegenerationRequestedPayload;
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

    private static final String DISASTER_ACTIVE_PREFIX     = "disaster:active:";
    private static final String DISASTER_ALERT_LIST_PREFIX = "disaster:alert:list:";
    private static final String DISASTER_DETAIL_PREFIX     = "disaster:detail:";
    private static final String DISASTER_LATEST_PREFIX     = "disaster:latest:";

    private final DisasterReadModelService disasterReadModelService;
    private final ObjectMapper objectMapper;

    @Override
    public EventType supportedEventType() {
        return EventType.CacheRegenerationRequested;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        CacheRegenerationRequestedPayload payload = parsePayload(envelope);
        String cacheKey = payload.cacheKey();
        log.info("Handling CacheRegenerationRequested (readmodel-worker): cacheKey={}, traceId={}",
            cacheKey, envelope.getTraceId());

        if (cacheKey.startsWith(DISASTER_ACTIVE_PREFIX)) {
            String region = cacheKey.substring(DISASTER_ACTIVE_PREFIX.length());
            disasterReadModelService.rebuildActiveList(region);
            return;
        }

        if (cacheKey.startsWith(DISASTER_ALERT_LIST_PREFIX)) {
            String remainder = cacheKey.substring(DISASTER_ALERT_LIST_PREFIX.length());
            int lastColon = remainder.lastIndexOf(':');
            if (lastColon < 0) {
                throw new EventProcessingException(
                    "CacheRegenerationRequested: invalid disaster:alert:list key format: " + cacheKey);
            }
            String region = remainder.substring(0, lastColon);
            String disasterType = remainder.substring(lastColon + 1);
            disasterReadModelService.rebuildAlertList(region, disasterType);
            return;
        }

        if (cacheKey.startsWith(DISASTER_DETAIL_PREFIX)) {
            String idStr = cacheKey.substring(DISASTER_DETAIL_PREFIX.length());
            Long alertId = parseId(idStr, cacheKey);
            disasterReadModelService.rebuildDetail(alertId);
            return;
        }

        if (cacheKey.startsWith(DISASTER_LATEST_PREFIX)) {
            String remainder = cacheKey.substring(DISASTER_LATEST_PREFIX.length());
            int firstColon = remainder.indexOf(':');
            if (firstColon < 0) {
                throw new EventProcessingException(
                    "CacheRegenerationRequested: invalid disaster:latest key format: " + cacheKey);
            }
            String disasterType = remainder.substring(0, firstColon);
            String region = remainder.substring(firstColon + 1);
            disasterReadModelService.rebuildLatest(disasterType, region);
            return;
        }

        log.warn("CacheRegenerationRequested: unhandled cacheKey prefix for readmodel-worker, no-op: cacheKey={}, traceId={}",
            cacheKey, envelope.getTraceId());
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
