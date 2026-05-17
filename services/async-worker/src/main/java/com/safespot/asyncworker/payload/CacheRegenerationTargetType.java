package com.safespot.asyncworker.payload;

import com.safespot.asyncworker.exception.EventProcessingException;

public enum CacheRegenerationTargetType {
    SHELTER_DETAIL,
    SHELTER_STATUS,
    SHELTER_MAP_ITEMS,
    SHELTER_GEO_INDEX,
    SHELTER_MAP_TILES;

    public static CacheRegenerationTargetType from(String value) {
        if (value == null || value.isBlank()) {
            throw new EventProcessingException("CacheRegenerationRequested: targetType is blank");
        }
        try {
            return CacheRegenerationTargetType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new EventProcessingException("CacheRegenerationRequested: unsupported targetType: " + value, e);
        }
    }
}
