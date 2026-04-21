package com.safespot.externalingestion.normalizer;

import com.safespot.externalingestion.domain.entity.ExternalApiRawPayload;

public interface Normalizer {
    String getSourceCode();
    NormalizationResult normalize(ExternalApiRawPayload raw);
}
