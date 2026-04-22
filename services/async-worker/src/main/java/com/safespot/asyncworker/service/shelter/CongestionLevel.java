package com.safespot.asyncworker.service.shelter;

public enum CongestionLevel {
    AVAILABLE, NORMAL, CROWDED, FULL;

    public static CongestionLevel of(int currentOccupancy, int capacity) {
        if (capacity <= 0) return FULL;
        double rate = (double) currentOccupancy / capacity * 100.0;
        if (rate < 50.0)  return AVAILABLE;
        if (rate < 75.0)  return NORMAL;
        if (rate < 100.0) return CROWDED;
        return FULL;
    }
}
