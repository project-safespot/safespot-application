package com.safespot.apipublicread.service;

import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.dto.DisasterCacheWarmupRequest;
import com.safespot.apipublicread.event.DisasterWarmupPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DisasterCacheWarmupService {

    private static final String WARMUP_SUPPRESS_KEY = "disaster:warmup:all";
    private static final Logger log = LoggerFactory.getLogger(DisasterCacheWarmupService.class);

    private final SuppressWindowService suppressWindowService;
    private final DisasterWarmupPublisher disasterWarmupPublisher;

    public boolean requestWarmup(DisasterCacheWarmupRequest request) {
        if (!suppressWindowService.tryPublish(WARMUP_SUPPRESS_KEY)) {
            log.info("[DisasterWarmup] suppressed: limit={}, includeDetails={}", request.limit(), request.includeDetails());
            return false;
        }
        disasterWarmupPublisher.publish(request.limit(), request.includeDetails());
        return true;
    }
}
