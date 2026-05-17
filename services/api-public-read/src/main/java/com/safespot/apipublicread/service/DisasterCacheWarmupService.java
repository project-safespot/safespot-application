package com.safespot.apipublicread.service;

import com.safespot.apipublicread.cache.SuppressWindowService;
import com.safespot.apipublicread.dto.DisasterCacheWarmupRequest;
import com.safespot.apipublicread.event.DisasterWarmupPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisasterCacheWarmupService {

    private static final String WARMUP_SUPPRESS_KEY = "disaster:warmup:all";

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
