package com.safespot.externalingestion.queue;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class NormalizationMessage {
    private final Long rawId;
    private final Long sourceId;
    private final Long executionId;
    private final String traceId;

    public static NormalizationMessage of(Long rawId, Long sourceId, Long executionId, String traceId) {
        return new NormalizationMessage(rawId, sourceId, executionId, traceId);
    }
}
