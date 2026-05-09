package com.safespot.scenariosimulator.event.payload;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class CacheRegenerationRequestedPayload {

    private String cacheKey;
    private String cacheKeyFamily;
    private OffsetDateTime requestedAt;
    private String reason;
    private int schemaVersion;
}
