package com.safespot.externalingestion.normalizer;

import org.springframework.stereotype.Component;

@Component
public class SeoulScopePolicy {

    public boolean isInScope(String sourceRegion) {
        return sourceRegion != null && sourceRegion.contains("서울");
    }
}
