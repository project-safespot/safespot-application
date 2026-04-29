package com.safespot.asyncworker.service.shelter;

public enum CongestionLevel {
    AVAILABLE, NORMAL, CROWDED, FULL;

    // capacity null (DB V3 nullable) → AVAILABLE
    // api-public-read fallback 경로(capacity <= 0 → AVAILABLE)와 일치시킴
    public static CongestionLevel of(int currentOccupancy, Integer capacity) {
        if (capacity == null || capacity <= 0) return AVAILABLE;
        double rate = (double) currentOccupancy / capacity * 100.0;
        if (rate < 50.0)  return AVAILABLE;
        if (rate < 75.0)  return NORMAL;
        if (rate < 100.0) return CROWDED;
        return FULL;
    }
}
